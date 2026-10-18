package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 확정된 계획이 상담에서 합의한 "쓸 수 있는 시간"(TIME_BUDGET)을 다시 찾는다. 배치가 "밤 12시까지"·"20시부터" 같은 시간대를 지키려고 쓴다.
 *
 * <p>계획 → 원본 초안(sourceProposalId) → 요청 맥락(briefId·흐름) → 합의 순으로 따라간다. 초안을 만들 때와 같은 규칙
 * ({@link PlanBriefService.View#effectiveFor})으로 고르므로, 생성 예산과 배치가 같은 합의를 본다. 어느 고리든 없으면 null — 그때 배치는
 * 하루 기본 창을 그대로 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanStudyTimeResolver {

    private final AiProposalMapper proposalMapper;
    private final PlanBriefService planBriefService;

    private final ObjectMapper requestJson = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PlanBriefService.TimeBudget resolve(Long userId, PlanVersion plan) {
        if (plan.getSourceProposalId() == null) {
            return null;
        }
        AiProposal proposal = proposalMapper.findByIdAndUserId(plan.getSourceProposalId(), userId);
        if (proposal == null || proposal.getPlanRequestJson() == null || proposal.getPlanRequestJson().isBlank()) {
            return null;
        }
        PlanRequestContext context;
        try {
            context = requestJson.readValue(proposal.getPlanRequestJson(), PlanRequestContext.class);
        } catch (Exception e) {
            log.warn("배치: 계획 요청 맥락을 읽지 못했다: {}", e.getClass().getSimpleName());
            return null;
        }
        if (context.briefId() == null) {
            return null;
        }
        PlanBriefService.View brief = planBriefService.loadById(userId, context.briefId());
        Long flowRoot = Objects.requireNonNullElse(context.flowRootProposalId(), proposal.getProposalId());
        return PlanBriefService.timeBudgetOf(brief.effectiveFor(plan.getStartDate(), plan.getEndDate(), flowRoot)
                .stream().map(PlanBriefService.Applicable::item).toList());
    }
}
