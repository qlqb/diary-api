package com.jungwoo.project.memo.orchestration;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.learning.LearningRecommendationService;
import com.jungwoo.project.memo.learning.dto.StudyRecommendationResponse;
import com.jungwoo.project.memo.planning.PlanningAgentService;
import com.jungwoo.project.memo.planning.dto.PlanningDraftResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "이번 주 자료구조 뭐 공부할지 보고 일정까지 짜줘" 같은 복합 요청을 위한 결정론적 라우팅.
 *
 * Agent가 서로를 자유롭게 재귀 호출하지 않는다 — 이 클래스가 Learning -> Planning 순서와
 * 워크플로당 최대 Agent 호출 횟수(workflow.max-agent-calls)를 자바 코드로 강제한다. LLM
 * 기반 라우터로 나중에 교체할 수 있도록 이 클래스 하나만 교체 지점으로 남겨둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningWorkflowOrchestrator {

    private final LearningRecommendationService learningRecommendationService;
    private final PlanningAgentService planningAgentService;

    @Value("${workflow.max-agent-calls:4}")
    private int maxAgentCalls;

    public record RecommendAndPlanResult(StudyRecommendationResponse recommendation, PlanningDraftResponse plan) {
    }

    public RecommendAndPlanResult recommendAndPlan(Long userId, Long courseId, String planningInstruction) {
        String workflowId = UUID.randomUUID().toString();
        AtomicInteger callCount = new AtomicInteger(0);

        log.info("워크플로 시작: workflowId={}, userId={}, courseId={}, type=recommendAndPlan", workflowId, userId, courseId);

        StudyRecommendationResponse recommendation = callAgent(callCount, () ->
                learningRecommendationService.recommend(userId, courseId, workflowId));

        PlanningDraftResponse plan = callAgent(callCount, () ->
                planningAgentService.createDraft(userId, recommendation.getRecommendationId(), planningInstruction, workflowId));

        log.info("워크플로 종료: workflowId={}, agentCalls={}", workflowId, callCount.get());

        return new RecommendAndPlanResult(recommendation, plan);
    }

    private <T> T callAgent(AtomicInteger callCount, java.util.function.Supplier<T> agentCall) {
        if (callCount.incrementAndGet() > maxAgentCalls) {
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        return agentCall.get();
    }
}
