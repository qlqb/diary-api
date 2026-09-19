package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.ContextChangeSuggestionService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.BusinessException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.domain.FamiliarityAnswer;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.dto.PlanItemDraft;
import com.jungwoo.project.memo.plan.dto.PlanStrategyResponse;
import com.jungwoo.project.memo.plan.dto.PlanJudgmentResult;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.PlanProvenanceCodec;
import com.jungwoo.project.memo.plan.provenance.ServerCalculation;
import com.jungwoo.project.memo.plan.dto.PlanRedraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanReviewState;
import com.jungwoo.project.memo.plan.selection.MaterialSelectionSummary;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 기간 계획 초안. 기존 PlanningAgentService.createDraft와 별개 경로다.
 *
 * <p>생성 규칙(컨텍스트·프롬프트·검증·정규화)은 {@link PeriodPlanDraftGenerator}에 있고, 이 서비스는 요청을 그 모양으로
 * 옮기고 결과를 저장한다. 계획 화면(/api/plans/draft)과 AI 대화가 둘 다 이 서비스를 거친다.
 *
 * <p>★ 모델 호출은 트랜잭션 밖이다. 생성(수십 초, 모델 2~4회)을 트랜잭션 안에서 돌리면 커넥션·행 잠금을 그동안 붙잡고,
 * 실패한 호출의 사용 기록까지 함께 롤백된다. 그래서 읽기(스냅샷) → 생성(밖) → 짧은 저장(트랜잭션) 순이다.
 *
 * <p>이 서비스는 execution_items도 plan_versions도 만들지 않는다. ai_proposals만 만들고, 실제 데이터는 사용자가
 * 확정(PlanConfirmService)해야 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftService {

    private final PeriodPlanDraftGenerator generator;
    private final AiConsultationClient aiConsultationClient;
    private final AiProposalService aiProposalService;
    private final AiProposalMapper aiProposalMapper;
    private final PlanVersionService planVersionService;
    private final PlanStrategyCodec strategyCodec;
    private final PlanBlockGeneratorV0 blockGeneratorV0;
    private final PlanningContextBuilder planningContextBuilder;
    private final PlanJudgmentService planJudgmentService;
    private final ContextChangeSuggestionService contextChangeSuggestionService;
    private final PlanItemService planItemService;
    private final PlanProvenanceCodec provenanceCodec;
    private final PlanMaterialContextService materialContextService;
    private final PlanGenerationProgress progress;
    private final com.jungwoo.project.memo.ai.brief.PlanBriefService planBriefService;
    /**
     * 저장 구간의 실제 트랜잭션 경계. createDraft·redraft가 같은 클래스의 persist를 직접 부르므로 @Transactional은
     * 프록시를 거치지 않아 적용되지 않았다(제안 저장만 따로 커밋되고 계획 메타·요청 맥락·옛 초안 폐기는 각각 autocommit).
     * 이 템플릿이 그 구간을 하나로 묶는다. 모델 호출·자료 선택은 여전히 이 밖이다.
     */
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    private final ObjectMapper requestJson = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 어느 경로로 초안을 만들 것인가. AI(기본) · V0 · JUDGMENT · V1.
     *
     * <p>AI가 운영 경로다. V0는 모델 없는 전환 검증용 기준선, JUDGMENT·V1은 판단층 측정용이다(13-plan-judgment.md §8.2).
     */
    @Value("${plan.draft.generator:AI}")
    private String generatorMode = "AI";

    // ===== 계획 화면 =====

    /** 계획 화면의 요청. 생성(트랜잭션 밖)과 저장(트랜잭션)을 나눠 부른다. 같은 요청 키면 다시 만들지 않는다. */
    public PlanDraftResponse createDraft(Long userId, PlanDraftRequest request) {
        String requestKey = request.getRequestKey();
        PlanDraftResponse existing = existingForKey(userId, requestKey, null);
        if (existing != null) {
            return existing;
        }
        if (!progress.start(requestKey, userId)) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_IN_PROGRESS);
        }
        try {
            Generated generated = generate(userId, request, new PeriodPlanDraftGenerator.Origin(null, null, requestKey, null),
                    null, stage -> progress.stage(requestKey, stage));
            PlanDraftResponse response = persist(userId, generated, null, null);
            progress.done(requestKey, response.getProposalId());
            return response;
        } catch (RuntimeException e) {
            progress.failed(requestKey, e instanceof BusinessException b ? b.getErrorCode().getCode() : "ERROR");
            throw e;
        }
    }

    /**
     * 같은 요청 키로 이미 만든 열린 초안이 있으면 그것을 돌려준다(중복 클릭·재시도·늦은 응답).
     *
     * <p>키는 사용자 안에서만 찾고(조회가 user_id로 걸러진다), 요청 대상도 대조한다 — 다시 만들기의 키가 다른 원본 초안의
     * 결과를 돌려주지 않게. 다시 만들기의 재시도는 원본이 이미 DISMISSED여도 여기서 먼저 답한다: 첫 시도가 성공했다면
     * 원본을 폐기한 것이 바로 그 첫 시도이기 때문이다.
     *
     * @param expectedPrevious 다시 만들기라면 원본 초안 id, 계획 화면의 새 요청이면 null
     */
    private PlanDraftResponse existingForKey(Long userId, String requestKey, Long expectedPrevious) {
        if (requestKey == null || requestKey.isBlank()) {
            return null;
        }
        AiProposal proposal = aiProposalMapper.findProposedByRequestKey(userId, requestKey);
        if (proposal == null) {
            return null;
        }
        PlanRequestContext stored = readRequestContext(proposal.getPlanRequestJson());
        Long storedPrevious = stored == null ? null : stored.previousProposalId();
        if (!java.util.Objects.equals(storedPrevious, expectedPrevious)) {
            log.warn("계획 초안: 요청 키 {}의 초안(proposalId={})은 다른 대상(previous={})의 결과라 돌려주지 않는다. 기대={}",
                    requestKey, proposal.getProposalId(), storedPrevious, expectedPrevious);
            throw new ConflictException(ErrorCode.PLAN_DRAFT_IN_PROGRESS);
        }
        log.info("계획 초안: 같은 요청 키의 초안을 돌려준다. userId={}, requestKey={}, proposalId={}", userId, requestKey,
                proposal.getProposalId());
        return loadDraft(userId, proposal.getProposalId());
    }

    /** 진행 상태. 키를 모르면 empty. */
    public java.util.Optional<PlanGenerationProgress.State> progressOf(Long userId, String requestKey) {
        return progress.find(requestKey, userId);
    }

    /**
     * 요청을 검증하고 모델을 불러 초안을 만든다. DB에 쓰지 않는다. 대화 경로가 이 단계와 {@link #persist}를 나눠 부른다.
     */
    public Generated generate(Long userId, PlanDraftRequest request) {
        return generate(userId, request, null, null, null);
    }

    /**
     * @param origin   요청의 출처(대화·요청 키·대체하는 초안). 없으면 계획 화면의 첫 요청
     * @param previous 대체하는 초안의 요청 맥락(근거 스냅샷). 없으면 null
     * @param stage    진행 단계 콜백. 없으면 null
     */
    public Generated generate(Long userId, PlanDraftRequest request, PeriodPlanDraftGenerator.Origin origin,
                              PlanRequestContext previous, Consumer<PlanGenerationProgress.Stage> stage) {
        PeriodPlanDraftGenerator.validatePeriod(request.getStartDate(), request.getEndDate());
        PlanIntensity intensity = planVersionService.resolveIntensity(userId, request.getIntensity());
        Spec spec = new Spec(userId, request.getStartDate(), request.getEndDate(), intensity,
                request.getInstruction(), request.getTitle(), request.getCourseIds(),
                request.getExcludeTopicIds() == null ? List.of() : request.getExcludeTopicIds(),
                request.getRequestedMaterialIds() == null ? List.of() : request.getRequestedMaterialIds(),
                request.getRequestedSectionIds() == null ? List.of() : request.getRequestedSectionIds(),
                origin);

        if ("V0".equalsIgnoreCase(generatorMode)) {
            log.info("기간 계획 초안: v0 결정적 생성기로 만든다. userId={}, {}~{}", userId, spec.start(), spec.end());
            return blockGeneratorV0.generate(spec);
        }
        if ("JUDGMENT".equalsIgnoreCase(generatorMode) || "V1".equalsIgnoreCase(generatorMode)) {
            return generateWithJudgment(spec, request, "V1".equalsIgnoreCase(generatorMode));
        }
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return generator.generate(spec, new PeriodPlanDraftGenerator.Options(previous, stage));
    }

    /** 판단 → 조각(측정용 경로). 서버 코드가 순서를 부른다. */
    private Generated generateWithJudgment(Spec spec, PlanDraftRequest request, boolean generateItems) {
        PlanningContext context = planningContextBuilder.build(spec);
        if (request.getFamiliarityAnswer() == FamiliarityAnswer.FAMILIAR) {
            String statement = planJudgmentService.familiarityStatement(
                    context, request.getFamiliarityTopicIds());
            if (statement != null) {
                contextChangeSuggestionService.recordUserConfirmed(spec.userId(), statement);
                context = planningContextBuilder.build(spec);
            }
        }
        PlanJudgmentResult judgment = planJudgmentService.judge(
                context, request.getFamiliarityAnswer() != null);
        if (judgment.isAsk()) {
            log.info("기간 계획 초안: 되묻고 끝낸다. userId={}, reason={}", spec.userId(), judgment.ask().reason());
            return Generated.asking(spec, judgment.ask());
        }
        if (!generateItems) {
            return blockGeneratorV0.generate(spec, context, judgment.strategy());
        }
        return withItems(spec, context, judgment.strategy());
    }

    private Generated withItems(Spec spec, PlanningContext context, PlanStrategy strategy) {
        List<PlanItemDraft> drafts = planItemService.generate(strategy, context, spec.maxItems());
        List<ProposalItem> items = new ArrayList<>();
        for (PlanItemDraft draft : drafts) {
            items.add(draft.toProposalItem());
        }
        int available = PeriodPlanDraftGenerator.availableMinutes(context.availability().windows());
        int target = spec.intensity().targetMinutesFor(available);
        String confidence = PeriodPlanDraftGenerator.confidenceSummary(context.availability().windows());
        log.info("기간 계획 초안(V1): userId={}, {}~{}, 조각={}개({}분), 가용={}분, 예산={}분",
                spec.userId(), spec.start(), spec.end(), items.size(),
                items.stream().mapToInt(ProposalItem::expectedMinutes).sum(), available, target);
        return new Generated(spec, target, target, null, false,
                spec.title() != null && !spec.title().isBlank() ? spec.title() : strategy.goal(),
                strategy.goal(), items,
                available, confidence, Math.max(0, available - target), items.isEmpty(),
                false, strategy, null);
    }

    // ===== 다시 만들기 =====

    /** 판단은 그대로 두고 조각만 다시 만든다(판단 경로). */
    public PlanDraftResponse regenerateItems(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        /*
         * 요청이 저장된 초안(새 운영 경로)은 옛 조각 재생성으로 가지 않는다 — 그 경로는 지시·과목 범위·상담 출처·합의·실행
         * 기록·기존 항목 조정을 모르는 Spec으로 옛 생성기를 부른다. 저장된 요청 맥락이 있으면 같은 조건으로 다시 만들기가
         * 곧 "표식을 반영한 재생성"이다(2026-09-17 지시서 §4). 옛 UI가 이 엔드포인트를 불러도 우회가 되지 않는다.
         */
        if (readRequestContext(proposal.getPlanRequestJson()) != null) {
            log.info("조각만 재생성 요청을 같은 조건으로 다시 만들기로 보낸다: userId={}, proposalId={}", userId, proposalId);
            return redraft(userId, proposalId, PlanRedraftRequest.builder().requestKey("regen-" + proposalId + "-"
                    + java.util.UUID.randomUUID()).build());
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }
        PlanStrategy strategy = strategyCodec.fromJson(proposal.getPlanStrategyJson());
        if (strategy == null || proposal.getPlanStartDate() == null || proposal.getPlanEndDate() == null
                || strategy.courses() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Spec spec = new Spec(userId, proposal.getPlanStartDate(), proposal.getPlanEndDate(),
                proposal.getPlanIntensity(), null, null, List.of());
        PlanningContext context = planningContextBuilder.build(spec);
        Generated generated = withItems(spec, context, planJudgmentService.applyUserMarks(strategy, context));
        log.info("조각만 재생성: userId={}, 원본 proposalId={}", userId, proposalId);
        return persist(userId, generated, null, null, proposalId);
    }

    /**
     * 같은 조건으로 다시 만들기. 「이번만 빼기」·되돌리기·「이미 알아요」 뒤 재생성이 쓴다(계획 화면·상담 초안 공통).
     *
     * <p>기간·강도·범위·지시·지정 자료는 이 초안을 만든 요청(plan_request_json)을 그대로 쓴다. 근거 스냅샷도 함께 넘겨
     * 달라진 것이 없으면 자료 선택을 생략하고, 달라졌으면 그 점을 초안에 표시한다.
     *
     * <p>모델 호출은 트랜잭션 밖이다. 실패하면 아무것도 바뀌지 않는다(기존 초안은 그대로 PROPOSED). 성공하면 짧은
     * 트랜잭션에서 옛 초안이 아직 PROPOSED인지 잠그고 본 뒤 새 초안을 저장하고 옛 초안을 폐기한다.
     */
    public PlanDraftResponse redraft(Long userId, Long proposalId, PlanRedraftRequest body) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        // 같은 요청 키의 완료 결과가 있으면 원본 상태보다 먼저 본다 — 첫 시도가 성공해 원본이 DISMISSED가 됐다는 이유로
        // 재시도(늦은 응답·새로고침)를 막지 않는다.
        String requestKey = body == null ? null : body.getRequestKey();
        PlanDraftResponse existing = existingForKey(userId, requestKey, proposalId);
        if (existing != null) {
            return existing;
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        }
        PlanRequestContext context = readRequestContext(proposal.getPlanRequestJson());
        if (context == null) {
            throw new ConflictException(ErrorCode.PLAN_REDRAFT_CONTEXT_MISSING);
        }
        if (!progress.start(requestKey, userId)) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_IN_PROGRESS);
        }
        try {
            PlanDraftRequest request = PlanDraftRequest.builder()
                    .startDate(context.startDate())
                    .endDate(context.endDate())
                    .intensity(context.intensity())
                    .title(context.title())
                    .instruction(context.instruction())
                    .courseIds(context.courseIds())
                    .familiarityAnswer(context.familiarityAnswer())
                    .familiarityTopicIds(context.familiarityTopicIds())
                    .excludeTopicIds(body != null && body.getExcludeTopicIds() != null
                            ? body.getExcludeTopicIds() : context.excludeTopicIds())
                    .requestedMaterialIds(body != null && body.getRequestedMaterialIds() != null
                            ? body.getRequestedMaterialIds() : context.requestedMaterialIds())
                    .requestedSectionIds(context.requestedSectionIds())
                    .requestKey(requestKey)
                    .build();
            // 다시 만들기는 같은 초안 흐름이다 — 처음 초안 id를 흐름의 뿌리로 이어 간다(THIS_DRAFT 합의의 범위).
            Long flowRoot = context.flowRootProposalId() != null ? context.flowRootProposalId() : proposalId;
            PeriodPlanDraftGenerator.Origin origin = new PeriodPlanDraftGenerator.Origin(context.conversationId(),
                    null, requestKey, proposalId, flowRoot);
            Generated generated = generate(userId, request, origin, context, stage -> progress.stage(requestKey, stage));
            log.info("같은 조건으로 초안 다시 만들기: userId={}, 원본 proposalId={}, 제외={}개, 지정자료={}개, 선택 재사용={}",
                    userId, proposalId, request.getExcludeTopicIds() == null ? 0 : request.getExcludeTopicIds().size(),
                    request.getRequestedMaterialIds() == null ? 0 : request.getRequestedMaterialIds().size(),
                    generated.extras() != null && generated.extras().selectionReused());
            PlanDraftResponse response = persistSuperseding(userId, generated, context.conversationId(), proposalId);
            carryReviewState(userId, proposal, proposalId, response);
            progress.done(requestKey, response.getProposalId());
            return response;
        } catch (RuntimeException e) {
            progress.failed(requestKey, e instanceof BusinessException b ? b.getErrorCode().getCode() : "ERROR");
            throw e;
        }
    }

    /**
     * 옛 초안을 잠근 채(FOR UPDATE) 아직 PROPOSED인지 보고 → 새 제안·항목·계획 메타·요청 맥락 저장 → 옛 초안 폐기까지 한
     * 트랜잭션이다. 그 사이 확정·폐기됐으면(동시 요청) 409 — 늦은 결과는 저장되지 않고 최신 초안을 덮지 않는다. 서로
     * 다른 요청 키로 같은 원본을 동시에 대체해도 잠금이 직렬화하므로 유효한 대체 결과는 하나뿐이다.
     */
    public PlanDraftResponse persistSuperseding(Long userId, Generated generated, Long conversationId, Long supersededId) {
        return transactions.execute(status -> {
            AiProposal locked = aiProposalMapper.findByIdAndUserIdForUpdate(supersededId, userId);
            if (locked == null || locked.getStatus() != AiProposalStatus.PROPOSED) {
                throw new ConflictException(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
            }
            return persistInTransaction(userId, generated, conversationId, null, supersededId);
        });
    }

    PlanRequestContext readRequestContext(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return requestJson.readValue(json, PlanRequestContext.class);
        } catch (Exception e) {
            log.warn("계획 요청 맥락을 읽지 못했다: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String requestContextJson(Spec spec, Generated generated, Long conversationId) {
        PeriodPlanDraftGenerator.Extras extras = generated.extras();
        PeriodPlanDraftGenerator.Origin origin = spec.origin();
        try {
            return requestJson.writeValueAsString(new PlanRequestContext(PlanRequestContext.VERSION,
                    conversationId != null ? "CONVERSATION" : "PLAN_SCREEN", spec.start(), spec.end(),
                    spec.intensity(), spec.title(), spec.instruction(), spec.courseIds(), spec.excludeTopicIds(),
                    spec.requestedMaterialIds(), spec.requestedSectionIds(), null, null, conversationId,
                    origin == null ? null : origin.requestKey(),
                    extras == null ? null : extras.briefId(), extras == null ? null : extras.briefVersion(),
                    origin == null ? null : origin.previousProposalId(),
                    generated.extras() == null ? null : generated.extras().evidence(),
                    origin == null ? null : origin.flowRootProposalId()));
        } catch (Exception e) {
            log.warn("계획 요청 맥락을 저장하지 못했다: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private PlanDraftResponse.RequestContextView requestContextView(Spec spec, MaterialSelectionSummary selection,
                                                                    Long conversationId, boolean redraftable) {
        java.util.Map<Long, String> titles = new java.util.HashMap<>();
        if (selection != null) {
            selection.excludedTopics().stream().filter(e -> "THIS_TIME".equals(e.reason()))
                    .forEach(e -> titles.put(e.topicId(), e.title()));
        }
        List<PlanDraftResponse.ExcludedTopic> excluded = (spec.excludeTopicIds() == null ? List.<Long>of() : spec.excludeTopicIds())
                .stream().distinct()
                .map(id -> new PlanDraftResponse.ExcludedTopic(id, titles.get(id)))
                .toList();
        return PlanDraftResponse.RequestContextView.builder()
                .source(conversationId != null ? "CONVERSATION" : "PLAN_SCREEN")
                .courseIds(spec.courseIds())
                .excludedTopics(excluded)
                .requestedMaterials(selection == null ? List.of() : selection.requestedMaterials())
                .redraftable(redraftable)
                .build();
    }

    // ===== 저장 =====

    /**
     * 생성 결과를 제안과 계획 메타데이터로 저장한다. 어느 진입점이든 이 한 곳을 지난다.
     *
     * @param conversationId  대화에서 만들었으면 그 대화. 계획 화면이면 null.
     * @param sourceMessageId 대화에서 만들었으면 그 ASSISTANT 메시지. 계획 화면이면 null.
     */
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId, Long sourceMessageId) {
        return persist(userId, generated, conversationId, sourceMessageId, null);
    }

    /**
     * 저장 구간. 상담 경로(AiTurnLifecycleService의 턴 완료 트랜잭션)에서 불리면 그 트랜잭션에 참여하고, 계획 화면 경로에서
     * 불리면 여기서 하나를 연다. 어느 쪽이든 제안·항목·계획 메타·요청 맥락·옛 초안 폐기가 함께 성공하거나 함께 되돌아간다.
     *
     * @param supersededProposalId 이 초안이 대체하는 기존 제안. 같은 트랜잭션에서 폐기한다
     */
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId,
                                     Long sourceMessageId, Long supersededProposalId) {
        return transactions.execute(status -> persistInTransaction(userId, generated, conversationId, sourceMessageId,
                supersededProposalId));
    }

    private PlanDraftResponse persistInTransaction(Long userId, Generated generated, Long conversationId,
                                                   Long sourceMessageId, Long supersededProposalId) {
        Spec spec = generated.spec();
        int days = spec.days();
        int maxItems = spec.maxItems();

        if (generated.ask() != null) {
            log.info("기간 계획 초안: 되묻기로 종료. userId={}, reason={}", userId, generated.ask().reason());
            return PlanDraftResponse.builder()
                    .startDate(spec.start()).endDate(spec.end()).days(days).intensity(spec.intensity())
                    .noAvailableTime(false)
                    .ask(generated.ask())
                    .build();
        }
        if (generated.noAvailableTime()) {
            log.info("기간 계획 초안: 가용시간 0으로 제안을 만들지 않음. userId={}, {}~{}", userId, spec.start(), spec.end());
            return PlanDraftResponse.builder()
                    .startDate(spec.start()).endDate(spec.end()).days(days).intensity(spec.intensity())
                    .baselineMinutes(0).targetMinutes(0)
                    .estimatedAvailableMinutes(generated.estimatedAvailableMinutes())
                    .availabilityConfidenceSummary(generated.availabilityConfidenceSummary())
                    .reservedBufferMinutes(0)
                    .noAvailableTime(true)
                    .suggestedTitle(generated.suggestedTitle())
                    .build();
        }

        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, conversationId, sourceMessageId, generated.items(), generated.adjustments(), spec.start(),
                List.of(), maxItems, evidenceJson(generated));

        aiProposalMapper.updatePlanMetadata(
                proposal.getProposalId(), userId, spec.start(), spec.end(), spec.intensity(),
                generated.targetMinutes(), strategyCodec.toJson(generated.strategy()),
                provenanceCodec.toJson(generated.provenance()));
        String requestContext = requestContextJson(spec, generated, conversationId);
        if (requestContext != null) {
            aiProposalMapper.updatePlanRequest(proposal.getProposalId(), userId, requestContext);
        }
        if (supersededProposalId != null) {
            aiProposalMapper.updateStatusAndRespondedAt(
                    supersededProposalId, userId, AiProposalStatus.DISMISSED, LocalDateTime.now());
        }
        if (generated.extras() != null && generated.extras().briefId() != null) {
            Long flowRoot = spec.origin() != null && spec.origin().flowRootProposalId() != null
                    ? spec.origin().flowRootProposalId() : proposal.getProposalId();
            planBriefService.markProposal(userId, generated.extras().briefId(), proposal.getProposalId(), flowRoot,
                    spec.start(), spec.end());
        }

        log.info("기간 계획 초안 생성: userId={}, proposalId={}, {}~{}({}일), intensity={}, target={}분, 항목={}개, 조정={}개, "
                        + "conversationId={}, 대체={}",
                userId, proposal.getProposalId(), spec.start(), spec.end(), days, spec.intensity(),
                generated.targetMinutes(), generated.items().size(), generated.adjustments().size(), conversationId,
                supersededProposalId);

        PeriodPlanDraftGenerator.Extras extras = generated.extras();
        return withItemReasons(userId, PlanDraftResponse.builder()
                .proposalId(proposal.getProposalId())
                .startDate(spec.start())
                .endDate(spec.end())
                .days(days)
                .intensity(spec.intensity())
                .baselineMinutes(generated.baselineMinutes())
                .targetMinutes(generated.targetMinutes())
                .targetMinutesReason(generated.targetMinutesReason())
                .estimatedAvailableMinutes(generated.estimatedAvailableMinutes())
                .availabilityConfidenceSummary(generated.availabilityConfidenceSummary())
                .reservedBufferMinutes(generated.reservedBufferMinutes())
                .noAvailableTime(false)
                .targetCappedByItemLimit(generated.targetCappedByItemLimit())
                .uncoveredMinutes(uncoveredMinutes(generated))
                .suggestedTitle(generated.suggestedTitle())
                .goalSummary(generated.goalSummary())
                .proposal(proposal)
                .strategy(PlanStrategyResponse.from(generated.strategy()))
                .pendingMaterials(pendingMaterials(userId, spec))
                .materialSelection(generated.materialSelection())
                .requestContext(requestContextView(spec, generated.materialSelection(), conversationId, requestContext != null))
                .generation(extras == null ? null : generationView(extras.budget(), extras.selectionReused()))
                .previousDraft(supersededProposalId == null ? null : PlanDraftResponse.PreviousDraftView.builder()
                        .proposalId(supersededProposalId)
                        .changes(extras == null ? List.of() : extras.changesFromPrevious())
                        .build())
                .briefId(extras == null ? null : extras.briefId())
                .briefVersion(extras == null ? null : extras.briefVersion())
                .build());
    }

    // ===== 검토 상태 =====

    /**
     * 검토 상태 저장. 열린(PROPOSED) 초안에만 쓴다. 클라이언트가 보낸 version이 저장된 것과 다르면 409 — 늦은 자동 저장이
     * 새 편집을 덮지 않는다. 저장은 실행 데이터를 바꾸지 않는다.
     */
    public PlanReviewState saveReviewState(Long userId, Long proposalId, PlanReviewState incoming) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        }
        PlanReviewState stored = readReviewState(proposal.getReviewStateJson());
        Integer storedVersion = stored == null ? null : stored.version();
        Integer sent = incoming == null ? null : incoming.version();
        if (!java.util.Objects.equals(storedVersion, sent)) {
            throw new ConflictException(ErrorCode.PLAN_REVIEW_STATE_STALE);
        }
        PlanReviewState next = new PlanReviewState(storedVersion == null ? 1 : storedVersion + 1,
                incoming == null ? null : blank(incoming.title()),
                incoming == null || incoming.excludedProposalItemIds() == null ? List.of()
                        : incoming.excludedProposalItemIds().stream().filter(java.util.Objects::nonNull).distinct().toList(),
                incoming == null || incoming.editedItems() == null ? List.of() : incoming.editedItems(),
                incoming == null || incoming.answers() == null ? java.util.Map.of() : incoming.answers(),
                LocalDateTime.now());
        String json;
        try {
            json = requestJson.writeValueAsString(next);
        } catch (Exception e) {
            throw new IllegalStateException("검토 상태 직렬화 실패", e);
        }
        int updated = aiProposalMapper.updateReviewState(proposalId, userId, json, storedVersion);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.PLAN_REVIEW_STATE_STALE);
        }
        return next;
    }

    PlanReviewState readReviewState(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return requestJson.readValue(json, PlanReviewState.class);
        } catch (Exception e) {
            log.warn("검토 상태를 읽지 못했다: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ===== 저장된 초안 다시 읽기 =====

    /**
     * 저장된 초안을 PlanDraftResponse 모양으로 다시 만든다. 새로고침·탭 이동 뒤 화면이 열린 초안을 되찾는 경로다.
     * 모델을 부르지 않는다. 자료 선택·호출 계측은 근거 스냅샷의 서버 계산에서 복원한다.
     */
    @Transactional(readOnly = true)
    /**
     * 옛 초안의 검토 상태(직접 고친 값·뺀 항목·제목·답)를 새 초안으로 옮긴다. 옮기지 못해도 다시 만들기는 성공이다.
     */
    private void carryReviewState(Long userId, AiProposal oldProposal, Long oldProposalId, PlanDraftResponse response) {
        try {
            PlanReviewState oldState = readReviewState(oldProposal.getReviewStateJson());
            if (oldState == null || response.getProposal() == null) {
                return;
            }
            AiProposalResponse oldItems = aiProposalService.get(oldProposalId, userId);
            ReviewStateCarryOver.Result carried = ReviewStateCarryOver.carry(oldState,
                    oldItems == null ? List.of() : oldItems.getItems(), response.getProposal().getItems());
            if (carried.state() != null) {
                String json = requestJson.writeValueAsString(carried.state());
                if (aiProposalMapper.updateReviewState(response.getProposalId(), userId, json, null) == 1) {
                    response.setReviewState(carried.state());
                }
            }
            response.setCarriedEdits(carried.carried());
            response.setEditConflicts(carried.conflicts());
            log.info("다시 만들기: 사용자의 검토 상태를 새 초안으로 옮겼다. {} -> {}, 옮김={}건, 충돌={}건", oldProposalId,
                    response.getProposalId(), carried.carried().size(), carried.conflicts().size());
        } catch (Exception e) {
            log.warn("다시 만들기: 검토 상태를 옮기지 못했다(초안은 만들어졌다). {} -> {}, {}", oldProposalId,
                    response.getProposalId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 이 초안을 만든 뒤 상담 합의나 그때 읽은 기억이 바뀌었는가. 바뀌었으면 STALE과 그 이유를 돌려준다.
     * 판단은 서버 기록의 비교다(합의 판 번호, 근거로 든 기억 행의 상태·수정 시각) — 모델에게 묻지 않는다.
     */
    PlanDraftResponse.Freshness freshnessOf(Long userId, AiProposal proposal, PlanRequestContext context,
                                            PlanProvenance provenance) {
        List<String> reasons = new ArrayList<>();
        try {
            if (context != null && context.briefId() != null && context.briefVersion() != null) {
                /*
                 * 판 번호만 비교하면 안 된다 — 초안을 만든 직후 서버가 합의에 "이 초안으로 이어짐"을 적으면서도 판이
                 * 오른다(실호출에서 방금 만든 초안이 곧바로 "갱신 필요"로 보였다). 초안이 저장된 뒤에 바뀐 합의 항목이
                 * 실제로 있는지를 본다.
                 */
                var brief = planBriefService.loadById(userId, context.briefId());
                java.time.LocalDateTime createdAt = proposal.getCreatedAt();
                if (brief != null && brief.version() > context.briefVersion() && createdAt != null
                        && brief.items().stream().anyMatch(i -> i.updatedAt() != null
                        && i.updatedAt().isAfter(createdAt.plusSeconds(2)))) {
                    reasons.add("이 초안을 만든 뒤 상담에서 조건이 바뀌었어요.");
                }
            }
            if (provenance != null && provenance.providedSources() != null && userContextMapper != null) {
                for (var source : provenance.providedSources()) {
                    if (source.sourceType() != com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType.USER_CONTEXT
                            || source.sourceId() == null) {
                        continue;
                    }
                    var row = userContextMapper.findByIdAndUserId(source.sourceId(), userId);
                    if (row == null) {
                        continue;
                    }
                    boolean changed = row.getStatus() != com.jungwoo.project.memo.ai.domain.UserContextStatus.ACTIVE
                            && row.getStatus() != com.jungwoo.project.memo.ai.domain.UserContextStatus.STALE;
                    if (changed) {
                        reasons.add("이 초안이 참고한 '내 상황'을 고치거나 지웠어요.");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("초안 최신성 판단 실패 — 최신으로 본다: proposalId={}", proposal.getProposalId());
        }
        return new PlanDraftResponse.Freshness(reasons.isEmpty() ? "CURRENT" : "STALE", reasons);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jungwoo.project.memo.ai.UserContextMapper userContextMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jungwoo.project.memo.ai.AiProposalItemMapper aiProposalItemMapper;

    /**
     * 항목 카드가 첫 화면에서 보여야 하는 근거 두 가지(선정 이유·출처 유형)를 항목 응답에 붙인다. 근거 원본은
     * evidence_json 하나다 — 저장 직후·재조회·다시 만들기 어느 경로로 와도 같은 값이 보이게 여기 한곳에서 읽는다.
     */
    private PlanDraftResponse withItemReasons(Long userId, PlanDraftResponse response) {
        if (response == null || response.getProposal() == null || response.getProposal().getItems() == null
                || aiProposalItemMapper == null) {
            return response;
        }
        try {
            java.util.Map<Long, com.jungwoo.project.memo.plan.provenance.PlanItemEvidence> byItem = new java.util.HashMap<>();
            for (var row : aiProposalItemMapper.findByProposalIdAndUserId(response.getProposalId(), userId)) {
                var evidence = provenanceCodec.evidenceFromJson(row.getEvidenceJson());
                if (evidence != null) {
                    byItem.put(row.getProposalItemId(), evidence);
                }
            }
            for (var item : response.getProposal().getItems()) {
                var evidence = byItem.get(item.getProposalItemId());
                if (evidence != null) {
                    item.setSelectionReason(evidence.reason());
                    item.setOrigin(evidence.origin());
                }
            }
        } catch (Exception e) {
            log.debug("초안 항목의 선정 이유를 붙이지 못했다: proposalId={}", response.getProposalId());
        }
        return response;
    }

    public PlanDraftResponse loadDraft(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        /*
         * 「이미 알아요」·되돌리기·다시 만들기는 새 초안을 만들고 옛 초안을 DISMISSED로 바꾼다. 상담 메시지나 세션이 옛 id를
         * 들고 있어도 지금 열린 초안(대체 사슬의 끝)을 돌려준다 — 새로고침이 옛 초안이 아니라 최신 초안을 되찾게.
         */
        int hops = 0;
        while (proposal.getStatus() == AiProposalStatus.DISMISSED && hops++ < 10) {
            AiProposal replacement = aiProposalMapper.findProposedReplacing(userId, proposal.getProposalId());
            if (replacement == null) {
                break;
            }
            log.info("저장된 초안 다시 읽기: proposalId={}는 {}로 대체됐다", proposal.getProposalId(), replacement.getProposalId());
            proposal = replacement;
        }
        if (proposal.getPlanStartDate() == null || proposal.getPlanEndDate() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        AiProposalResponse response = aiProposalService.get(proposal.getProposalId(), userId);
        PlanRequestContext context = readRequestContext(proposal.getPlanRequestJson());
        PlanProvenance provenance = provenanceCodec.fromJson(proposal.getPlanProvenanceJson());
        MaterialSelectionSummary selection = null;
        PlanDraftResponse.GenerationView generation = null;
        if (provenance != null && provenance.serverCalculations() != null) {
            for (ServerCalculation calc : provenance.serverCalculations()) {
                try {
                    if (calc.kind() == ServerCalculation.ServerCalculationKind.MATERIAL_SELECTION) {
                        selection = requestJson.convertValue(calc.result(), MaterialSelectionSummary.class);
                    } else if (calc.kind() == ServerCalculation.ServerCalculationKind.GENERATION_CALLS) {
                        GenerationBudget.Summary summary = requestJson.convertValue(calc.result(), GenerationBudget.Summary.class);
                        generation = generationView(summary, false);
                    }
                } catch (Exception e) {
                    log.debug("저장된 초안의 서버 계산을 복원하지 못했다: kind={}", calc.kind());
                }
            }
        }
        Spec spec = new Spec(userId, proposal.getPlanStartDate(), proposal.getPlanEndDate(), proposal.getPlanIntensity(),
                context == null ? null : context.instruction(), context == null ? null : context.title(),
                context == null ? List.of() : context.courseIds(),
                context == null || context.excludeTopicIds() == null ? List.of() : context.excludeTopicIds());
        int items = response.getItems() == null ? 0 : response.getItems().size();
        return withItemReasons(userId, PlanDraftResponse.builder()
                .proposalId(proposal.getProposalId())
                .startDate(spec.start()).endDate(spec.end()).days(spec.days()).intensity(spec.intensity())
                .baselineMinutes(proposal.getPlanTargetMinutes()).targetMinutes(proposal.getPlanTargetMinutes())
                .noAvailableTime(false)
                .suggestedTitle(spec.title() != null ? spec.title()
                        : spec.start().getMonthValue() + "월 " + spec.start().getDayOfMonth() + "일 ~ "
                        + spec.end().getMonthValue() + "월 " + spec.end().getDayOfMonth() + "일 계획")
                .proposal(response)
                .strategy(PlanStrategyResponse.from(strategyCodec.fromJson(proposal.getPlanStrategyJson())))
                .pendingMaterials(pendingMaterials(userId, spec))
                .materialSelection(selection)
                .requestContext(requestContextView(spec, selection, proposal.getConversationId(), context != null))
                .generation(generation)
                .previousDraft(context == null || context.previousProposalId() == null ? null
                        : PlanDraftResponse.PreviousDraftView.builder().proposalId(context.previousProposalId())
                        .changes(List.of()).build())
                .briefId(context == null ? null : context.briefId())
                .briefVersion(context == null ? null : context.briefVersion())
                .reviewState(readReviewState(proposal.getReviewStateJson()))
                .freshness(freshnessOf(userId, proposal, context, provenance))
                .build());
    }

    private static PlanDraftResponse.GenerationView generationView(GenerationBudget.Summary s, boolean reused) {
        if (s == null) {
            return null;
        }
        List<String> calls = new ArrayList<>();
        for (GenerationBudget.CallRecord r : s.calls() == null ? List.<GenerationBudget.CallRecord>of() : s.calls()) {
            calls.add(r.kind() + "#" + r.round() + (r.success() ? "" : "(실패)")
                    + (r.inputTokens() == null ? "" : " in=" + r.inputTokens())
                    + (r.outputTokens() == null ? "" : " out=" + r.outputTokens()) + " " + r.latencyMs() + "ms");
        }
        return PlanDraftResponse.GenerationView.builder()
                .normalCalls(s.normalCalls()).recoveryCalls(s.recoveryCalls())
                .maxNormalCalls(s.maxNormalCalls()).maxTotalCalls(s.maxTotalCalls())
                .retrievalRounds(s.retrievalRounds()).maxRetrievalRounds(s.maxRetrievalRounds())
                .inputTokens(s.inputTokens()).outputTokens(s.outputTokens()).elapsedMs(s.elapsedMs())
                .selectionReused(reused).calls(calls)
                .build();
    }

    private List<PlanDraftResponse.PendingMaterial> pendingMaterials(Long userId, Spec spec) {
        try {
            List<Long> courseIds = spec.courseIds() == null || spec.courseIds().isEmpty()
                    ? planningContextBuilder.resolveCourses(userId, List.of()).stream()
                    .map(com.jungwoo.project.memo.course.domain.Course::getCourseId).toList()
                    : spec.courseIds();
            return materialContextService.pendingMaterials(userId, courseIds).stream()
                    .map(p -> new PlanDraftResponse.PendingMaterial(p.materialId(), p.filename(), p.state(), p.courseId()))
                    .toList();
        } catch (Exception e) {
            log.debug("미반영 자료 조회 실패: userId={}", userId, e);
            return List.of();
        }
    }

    private List<String> evidenceJson(Generated generated) {
        List<PlanItemEvidence> evidence = generated.itemEvidence();
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        if (evidence.size() != generated.items().size()) {
            log.error("계획 초안: 조각 {}개와 근거 {}개의 수가 다르다 — 근거를 붙이지 않는다.",
                    generated.items().size(), evidence.size());
            return List.of();
        }
        List<String> json = new ArrayList<>();
        for (PlanItemEvidence one : evidence) {
            json.add(provenanceCodec.toJson(one));
        }
        return json;
    }

    private Integer uncoveredMinutes(Generated generated) {
        if (!generated.targetCappedByItemLimit()) {
            return null;
        }
        int wanted = generated.spec().intensity().targetMinutesFor(generated.estimatedAvailableMinutes());
        return Math.max(0, wanted - generated.targetMinutes());
    }
}
