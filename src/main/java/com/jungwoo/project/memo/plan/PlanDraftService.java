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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 기간 계획 초안. 기존 PlanningAgentService.createDraft와 별개 경로다 — 그쪽은
 * recommendationId가 필수라 여러 프로젝트를 아우를 수 없고, 기존 동작을 건드리지 않는다.
 *
 * <p>생성 규칙(컨텍스트·프롬프트·검증·정규화)은 {@link PeriodPlanDraftGenerator}에 있고, 이
 * 서비스는 요청을 그 모양으로 옮기고 결과를 저장한다. 계획 화면(/api/plans/draft)과 AI 대화가
 * 둘 다 이 서비스를 거치므로 어느 탭에서 시작하든 같은 제안과 같은 계획 메타데이터가 남는다.
 *
 * <p>이 서비스는 execution_items도 plan_versions도 만들지 않는다. ai_proposals만 만들고,
 * 실제 데이터는 사용자가 확정(PlanConfirmService)해야 생긴다.
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

    /**
     * 어느 경로로 초안을 만들 것인가. AI(기본) · V0 · JUDGMENT · V1.
     *
     * <p>넷은 계단이다. 뒤로 갈수록 모델이 하는 일이 늘고, 앞의 것과 비교하면 그 늘어난
     * 부분이 실제로 값을 하는지 따로 잴 수 있다(13-plan-judgment.md §8.2).
     *
     * <ul>
     *   <li><b>V0</b> 모델 없음. 마감이 제안→확정→Timefold까지 살아남는지 확인할 때 켠다 —
     *       모델을 끼우면 실패 원인이 "체인이 끊겼다"와 "모델이 마감을 안 냈다"로 갈린다.
     *   <li><b>JUDGMENT</b> 판단만 모델. 조각은 v0와 같은 방식이라 V0와의 차이가 전부
     *       판단에서 온다.
     *   <li><b>V1</b> 판단 + 조각 생성. JUDGMENT와의 차이가 전부 조각 생성에서 온다.
     *   <li><b>AI</b> 기존 단일 호출 경로. 운영 기본값이다.
     * </ul>
     */
    @Value("${plan.draft.generator:AI}")
    private String generatorMode = "AI";

    /** 계획 화면의 요청. 생성과 저장을 한 번에 한다. */
    @Transactional
    public PlanDraftResponse createDraft(Long userId, PlanDraftRequest request) {
        return persist(userId, generate(userId, request), null, null);
    }

    /**
     * 요청을 검증하고 모델을 불러 초안을 만든다. DB에 쓰지 않는다.
     *
     * <p>대화 경로는 이 단계와 {@link #persist}를 나눠 부른다 — 모델 호출은 턴 트랜잭션 밖에서,
     * 저장은 ASSISTANT 메시지와 같은 트랜잭션 안에서 해야 하기 때문이다.
     */
    public Generated generate(Long userId, PlanDraftRequest request) {
        PeriodPlanDraftGenerator.validatePeriod(request.getStartDate(), request.getEndDate());
        PlanIntensity intensity = planVersionService.resolveIntensity(userId, request.getIntensity());
        Spec spec = new Spec(userId, request.getStartDate(), request.getEndDate(), intensity,
                request.getInstruction(), request.getTitle(), request.getCourseIds());

        if ("V0".equalsIgnoreCase(generatorMode)) {
            log.info("기간 계획 초안: v0 결정적 생성기로 만든다. userId={}, {}~{}",
                    userId, spec.start(), spec.end());
            return blockGeneratorV0.generate(spec);
        }
        if ("JUDGMENT".equalsIgnoreCase(generatorMode) || "V1".equalsIgnoreCase(generatorMode)) {
            return generateWithJudgment(spec, request, "V1".equalsIgnoreCase(generatorMode));
        }
        // 모델이 설정돼 있어야 하는 것은 AI 경로뿐이다. v0는 모델을 부르지 않는다.
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return generator.generate(spec);
    }

    /**
     * 판단 → 조각. 서버 코드가 순서를 부른다 — 오케스트레이터도, 서로를 부르는 Agent도 없다.
     *
     * <p>되물어야 하면 조각을 만들지 않고 질문만 돌려준다. 일단 만들어 놓고 "이게 맞나요?"라고
     * 묻는 것과 다르다 — 만들어진 계획은 그 자체로 화면의 기준점이 되어, 사용자가 답을 고르기
     * 전에 이미 대답을 유도한다.
     */
    private Generated generateWithJudgment(Spec spec, PlanDraftRequest request, boolean generateItems) {
        PlanningContext context = planningContextBuilder.build(spec);

        /*
         * 「이미 익숙해요」는 저장할 사실이다. 맥락으로 남기고 컨텍스트를 다시 모은다 —
         * 그래야 이번 초안부터 그 사실이 근거가 된다. 답을 다음 계획에서야 반영하면 사용자는
         * 방금 알려준 것이 무시됐다고 본다.
         *
         * 「처음이에요」/「일부는 익숙해요」는 저장할 것이 없다. 전자는 근거 없음이 이미
         * 기본이고, 후자는 어느 것이 익숙한지를 이 답으로는 알 수 없다(그 자리는 항목별
         * 「이미 알아요」다). 되묻기만 멈춘다.
         */
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
            log.info("기간 계획 초안: 되묻고 끝낸다. userId={}, reason={}",
                    spec.userId(), judgment.ask().reason());
            return Generated.asking(spec, judgment.ask());
        }
        if (!generateItems) {
            // JUDGMENT 모드: 조각은 v0와 같은 방식으로 만든다. 판단의 기여만 따로 재기 위한
            // 경로이므로 조각 생성이 끼어들면 안 된다(13-plan-judgment.md §8.2).
            return blockGeneratorV0.generate(spec, context, judgment.strategy());
        }
        return withItems(spec, context, judgment.strategy());
    }

    /** 판단 → 조각. 완성형 경로다. */
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

    /**
     * 판단은 그대로 두고 조각만 다시 만든다. 후속 재계획의 원형이다.
     *
     * <p>기존 제안을 고치지 않고 새 제안을 만든 뒤 원본을 폐기한다 — 제안 항목은 각자 상태를
     * 갖고(적용됨·폐기됨) 그 위에 덮어쓰면 "무엇이 사용자에게 보였던 것인지"가 사라진다.
     * 둘을 한 트랜잭션에서 처리해 살아 있는 제안이 둘로 남는 상태를 만들지 않는다.
     *
     * <p>판단을 다시 하지 않는 것이 요점이다. 사용자가 「이미 알아요」로 가정 하나를 고쳤을 때
     * 목표와 과목 순서까지 흔들리면, 고친 것과 무관한 변화가 함께 와서 무엇 때문에 계획이
     * 바뀌었는지 알 수 없게 된다.
     */
    public PlanDraftResponse regenerateItems(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }
        PlanStrategy strategy = strategyCodec.fromJson(proposal.getPlanStrategyJson());
        if (strategy == null || proposal.getPlanStartDate() == null || proposal.getPlanEndDate() == null) {
            // 판단 없이 만든 초안이다. 다시 만들 근거가 없으므로 새 초안을 만들어야 한다.
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Spec spec = new Spec(userId, proposal.getPlanStartDate(), proposal.getPlanEndDate(),
                proposal.getPlanIntensity(), null, null, List.of());
        // 컨텍스트는 다시 모은다 — 그 사이 「이미 알아요」가 눌렸다면 그것이 반영돼야 한다.
        PlanningContext context = planningContextBuilder.build(spec);
        // 판단은 다시 하지 않고, 표식이 가리키는 항목의 취급만 서버가 내린다.
        Generated generated = withItems(spec, context,
                planJudgmentService.applyUserMarks(strategy, context));

        log.info("조각만 재생성: userId={}, 원본 proposalId={}", userId, proposalId);
        return persist(userId, generated, null, null, proposalId);
    }

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

    /**
     * @param supersededProposalId 이 초안이 대체하는 기존 제안. 같은 트랜잭션에서 폐기해
     *                             살아 있는 제안이 둘로 남지 않게 한다
     */
    @Transactional
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId,
                                     Long sourceMessageId, Long supersededProposalId) {
        Spec spec = generated.spec();
        int days = spec.days();
        int maxItems = spec.maxItems();

        if (generated.ask() != null) {
            // 되묻는 중이다. 제안을 만들지 않는다 — 만들어진 계획은 사용자가 답을 고르기 전에
            // 이미 화면의 기준점이 되어 대답을 유도한다.
            log.info("기간 계획 초안: 되묻기로 종료. userId={}, reason={}", userId, generated.ask().reason());
            return PlanDraftResponse.builder()
                    .startDate(spec.start()).endDate(spec.end()).days(days).intensity(spec.intensity())
                    .noAvailableTime(false)
                    .ask(generated.ask())
                    .build();
        }

        if (generated.noAvailableTime()) {
            // 남는 시간이 없으면 제안을 만들지 않는다. 실패가 아니라 "현재 추정으로는 배치할
            // 시간이 없다"는 안내이고, 화면이 가용시간 수정 경로를 보여준다.
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
                userId, conversationId, sourceMessageId, generated.items(), List.of(), spec.start(), List.of(),
                maxItems);

        // 판단은 제안에 얹어 둔다. 확정이 여기서 읽어 plan_versions로 옮기므로 클라이언트가
        // 다시 보낼 필요가 없고, 사용자가 화면에서 본 판단과 저장되는 판단이 갈라지지 않는다.
        aiProposalMapper.updatePlanMetadata(
                proposal.getProposalId(), userId, spec.start(), spec.end(), spec.intensity(),
                generated.targetMinutes(), strategyCodec.toJson(generated.strategy()));

        if (supersededProposalId != null) {
            aiProposalMapper.updateStatusAndRespondedAt(
                    supersededProposalId, userId, AiProposalStatus.DISMISSED, LocalDateTime.now());
        }

        log.info("기간 계획 초안 생성: userId={}, proposalId={}, {}~{}({}일), intensity={}, "
                        + "baseline={}분, target={}분, 조정={}, 항목={}개, conversationId={}",
                userId, proposal.getProposalId(), spec.start(), spec.end(), days, spec.intensity(),
                generated.baselineMinutes(), generated.targetMinutes(), generated.targetAdjusted(),
                generated.items().size(), conversationId);

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
                .build();
    }

    /**
     * 강도대로라면 담겼어야 하는데 상한 때문에 못 담은 시간. 상한에 걸리지 않았으면 null이다 —
     * 0을 보내면 화면이 "0분 못 담았다"는 줄을 그린다.
     */
    private Integer uncoveredMinutes(Generated generated) {
        if (!generated.targetCappedByItemLimit()) {
            return null;
        }
        int wanted = generated.spec().intensity().targetMinutesFor(generated.estimatedAvailableMinutes());
        return Math.max(0, wanted - generated.targetMinutes());
    }
}
