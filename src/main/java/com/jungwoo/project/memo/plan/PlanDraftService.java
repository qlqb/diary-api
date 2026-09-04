package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        PlanIntensity intensity = planVersionService.resolveIntensity(userId, request.getIntensity());
        Spec spec = new Spec(userId, request.getStartDate(), request.getEndDate(), intensity,
                request.getInstruction(), request.getTitle(), request.getCourseIds());
        return generator.generate(spec);
    }

    /**
     * 생성 결과를 제안과 계획 메타데이터로 저장한다. 어느 진입점이든 이 한 곳을 지난다.
     *
     * @param conversationId  대화에서 만들었으면 그 대화. 계획 화면이면 null.
     * @param sourceMessageId 대화에서 만들었으면 그 ASSISTANT 메시지. 계획 화면이면 null.
     */
    @Transactional
    public PlanDraftResponse persist(Long userId, Generated generated, Long conversationId, Long sourceMessageId) {
        Spec spec = generated.spec();
        int days = spec.days();
        int maxItems = spec.maxItems();

        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, conversationId, sourceMessageId, generated.items(), List.of(), spec.start(), List.of(),
                maxItems);

        aiProposalMapper.updatePlanMetadata(
                proposal.getProposalId(), userId, spec.start(), spec.end(), spec.intensity(),
                generated.targetMinutes());

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
                .suggestedTitle(generated.suggestedTitle())
                .goalSummary(generated.goalSummary())
                .proposal(proposal)
                .build();
    }
}
