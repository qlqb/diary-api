package com.jungwoo.project.memo.orchestration;

import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.learning.LearningRecommendationService;
import com.jungwoo.project.memo.learning.dto.StudyRecommendationResponse;
import com.jungwoo.project.memo.planning.PlanningAgentService;
import com.jungwoo.project.memo.planning.dto.PlanningDraftResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestrator는 Agent 호출 순서와 워크플로당 최대 호출 횟수를 결정론적으로 통제한다 —
 * Agent끼리 서로 재귀 호출하지 않고, 앞 단계가 실패하면 뒤 단계를 아예 부르지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class LearningWorkflowOrchestratorTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long RECOMMENDATION_ID = 200L;

    @Mock private LearningRecommendationService learningRecommendationService;
    @Mock private PlanningAgentService planningAgentService;

    @InjectMocks
    private LearningWorkflowOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orchestrator, "maxAgentCalls", 4);
    }

    @Test
    void recommendAndPlan_callsLearningThenPlanning_inOrder() {
        StudyRecommendationResponse recommendation = StudyRecommendationResponse.builder()
                .recommendationId(RECOMMENDATION_ID).courseId(COURSE_ID).build();
        when(learningRecommendationService.recommend(eq(USER_ID), eq(COURSE_ID), anyString()))
                .thenReturn(recommendation);
        PlanningDraftResponse plan = PlanningDraftResponse.builder().recommendationId(RECOMMENDATION_ID).build();
        when(planningAgentService.createDraft(eq(USER_ID), eq(RECOMMENDATION_ID), any(), anyString()))
                .thenReturn(plan);

        LearningWorkflowOrchestrator.RecommendAndPlanResult result =
                orchestrator.recommendAndPlan(USER_ID, COURSE_ID, "이번 주에 계획해줘");

        assertThat(result.recommendation()).isSameAs(recommendation);
        assertThat(result.plan()).isSameAs(plan);

        InOrder order = inOrder(learningRecommendationService, planningAgentService);
        order.verify(learningRecommendationService).recommend(eq(USER_ID), eq(COURSE_ID), anyString());
        order.verify(planningAgentService).createDraft(eq(USER_ID), eq(RECOMMENDATION_ID), any(), anyString());
    }

    @Test
    void recommendAndPlan_neverCallsPlanning_whenLearningAgentFails() {
        when(learningRecommendationService.recommend(eq(USER_ID), eq(COURSE_ID), anyString()))
                .thenThrow(new ServiceUnavailableException(com.jungwoo.project.memo.common.exception.ErrorCode.AI_GENERATION_FAILED));

        assertThatThrownBy(() -> orchestrator.recommendAndPlan(USER_ID, COURSE_ID, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(planningAgentService, never()).createDraft(any(), any(), any(), any());
    }

    @Test
    void recommendAndPlan_stopsWorkflow_whenMaxAgentCallsExceeded() {
        ReflectionTestUtils.setField(orchestrator, "maxAgentCalls", 1);
        when(learningRecommendationService.recommend(eq(USER_ID), eq(COURSE_ID), anyString()))
                .thenReturn(StudyRecommendationResponse.builder().recommendationId(RECOMMENDATION_ID).build());

        assertThatThrownBy(() -> orchestrator.recommendAndPlan(USER_ID, COURSE_ID, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(planningAgentService, never()).createDraft(any(), any(), any(), any());
    }
}
