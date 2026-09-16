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
        PlanDraftResponse existing = existingForKey(userId, requestKey);
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

    /** 같은 요청 키로 이미 만든 열린 초안이 있으면 그것을 돌려준다(중복 클릭·재시도·늦은 응답). */
    private PlanDraftResponse existingForKey(Long userId, String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            return null;
        }
        AiProposal proposal = aiProposalMapper.findProposedByRequestKey(userId, requestKey);
        if (proposal == null) {
            return null;
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
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        }
        PlanRequestContext context = readRequestContext(proposal.getPlanRequestJson());
        if (context == null) {
            throw new ConflictException(ErrorCode.PLAN_REDRAFT_CONTEXT_MISSING);
        }
        String requestKey = body == null ? null : body.getRequestKey();
        PlanDraftResponse existing = existingForKey(userId, requestKey);
        if (existing != null) {
            return existing;
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
            PeriodPlanDraftGenerator.Origin origin = new PeriodPlanDraftGenerator.Origin(context.conversationId(),
                    null, requestKey, proposalId);
            Generated generated = generate(userId, request, origin, context, stage -> progress.stage(requestKey, stage));
            log.info("같은 조건으로 초안 다시 만들기: userId={}, 원본 proposalId={}, 제외={}개, 지정자료={}개, 선택 재사용={}",
                    userId, proposalId, request.getExcludeTopicIds() == null ? 0 : request.getExcludeTopicIds().size(),
                    request.getRequestedMaterialIds() == null ? 0 : request.getRequestedMaterialIds().size(),
                    generated.extras() != null && generated.extras().selectionReused());
            PlanDraftResponse response = persistSuperseding(userId, generated, context.conversationId(), proposalId);
            progress.done(requestKey, response.getProposalId());
            return response;
        } catch (RuntimeException e) {
            progress.failed(requestKey, e instanceof BusinessException b ? b.getErrorCode().getCode() : "ERROR");
            throw e;
        }
    }

    /** 옛 초안이 그 사이 확정·폐기됐으면(동시 요청) 409 — 늦은 결과가 최신 초안을 덮지 않는다. */
    @Transactional
    public PlanDraftResponse persistSuperseding(Long userId, Generated generated, Long conversationId, Long supersededId) {
        AiProposal locked = aiProposalMapper.findByIdAndUserIdForUpdate(supersededId, userId);
        if (locked == null || locked.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        }
        return persist(userId, generated, conversationId, null, supersededId);
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
                    extras == null ? null : extras.evidence()));
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
    @Transactional
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId, Long sourceMessageId) {
        return persist(userId, generated, conversationId, sourceMessageId, null);
    }

    /** @param supersededProposalId 이 초안이 대체하는 기존 제안. 같은 트랜잭션에서 폐기한다 */
    @Transactional
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId,
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
            planBriefService.markProposal(userId, generated.extras().briefId(), proposal.getProposalId());
        }

        log.info("기간 계획 초안 생성: userId={}, proposalId={}, {}~{}({}일), intensity={}, target={}분, 항목={}개, 조정={}개, "
                        + "conversationId={}, 대체={}",
                userId, proposal.getProposalId(), spec.start(), spec.end(), days, spec.intensity(),
                generated.targetMinutes(), generated.items().size(), generated.adjustments().size(), conversationId,
                supersededProposalId);

        PeriodPlanDraftGenerator.Extras extras = generated.extras();
        return PlanDraftResponse.builder()
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
                .build();
    }

    // ===== 저장된 초안 다시 읽기 =====

    /**
     * 저장된 초안을 PlanDraftResponse 모양으로 다시 만든다. 새로고침·탭 이동 뒤 화면이 열린 초안을 되찾는 경로다.
     * 모델을 부르지 않는다. 자료 선택·호출 계측은 근거 스냅샷의 서버 계산에서 복원한다.
     */
    @Transactional(readOnly = true)
    public PlanDraftResponse loadDraft(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getPlanStartDate() == null || proposal.getPlanEndDate() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        AiProposalResponse response = aiProposalService.get(proposalId, userId);
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
        return PlanDraftResponse.builder()
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
                .build();
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
