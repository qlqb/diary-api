package com.jungwoo.project.memo.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.learning.LearningRecommendationService;
import com.jungwoo.project.memo.learning.StudyRecommendationMapper;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.ActivityType;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.RecommendationPriority;
import com.jungwoo.project.memo.learning.domain.RecommendationStatus;
import com.jungwoo.project.memo.learning.domain.StudyRecommendation;
import com.jungwoo.project.memo.planning.dto.PlanningDraftResponse;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.service.SchedulePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Planning Agent: 계획 초안 생성이 기존 스케줄링 파이프라인만 태우고 ExecutionItem을 전혀
 * 만들지 않는지(Apply 전 미반영), Apply 이후에만 기존 AiProposalService.apply()를 거쳐
 * 실행 조각이 생기고 학습 topic이 연결되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PlanningAgentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long TOPIC_ID = 100L;
    private static final Long RECOMMENDATION_ID = 200L;
    private static final Long PROPOSAL_ID = 300L;

    @Mock private TopicService topicService;
    @Mock private LearningRecommendationService learningRecommendationService;
    @Mock private StudyRecommendationMapper studyRecommendationMapper;
    @Mock private AiProposalService aiProposalService;
    @Mock private SchedulePreviewService schedulePreviewService;
    @Mock private ExecutionItemService executionItemService;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private PlanningAgentService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultTimeZoneId", "Asia/Seoul");
        ReflectionTestUtils.setField(service, "maxHorizonDays", 7);
    }

    private StudyRecommendation suggestedRecommendation() {
        return StudyRecommendation.builder()
                .recommendationId(RECOMMENDATION_ID).userId(USER_ID).courseId(COURSE_ID).topicId(TOPIC_ID)
                .activityType(ActivityType.NEW_LEARNING)
                .recommendedMinutesMin(30).recommendedMinutesIdeal(45)
                .priority(RecommendationPriority.SHOULD)
                .reason("다음 단계로 적절함")
                .status(RecommendationStatus.SUGGESTED)
                .build();
    }

    @Test
    void createDraft_rejectsRecommendation_thatIsNotInSuggestedStatus() {
        StudyRecommendation planned = suggestedRecommendation();
        planned.setStatus(RecommendationStatus.PLANNED);
        when(learningRecommendationService.getOwned(USER_ID, RECOMMENDATION_ID)).thenReturn(planned);

        assertThatThrownBy(() -> service.createDraft(USER_ID, RECOMMENDATION_ID, null))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STUDY_RECOMMENDATION_NOT_SUGGESTED));

        verifyNoInteractions(aiProposalService, schedulePreviewService, executionItemService);
    }

    @Test
    void createDraft_neverCreatesExecutionItem_onlyBuildsProposalAndPreview() {
        when(learningRecommendationService.getOwned(USER_ID, RECOMMENDATION_ID)).thenReturn(suggestedRecommendation());
        when(topicService.getOwnedTopic(USER_ID, TOPIC_ID)).thenReturn(
                CourseTopic.builder().topicId(TOPIC_ID).courseId(COURSE_ID).title("이중 연결 리스트").build());

        AiProposalResponse proposalResponse = AiProposalResponse.builder()
                .proposalId(PROPOSAL_ID).targetScope(AiProposalTargetScope.EXECUTION)
                .status(AiProposalStatus.PROPOSED).items(List.of()).build();
        when(aiProposalService.createFromItems(eq(USER_ID), eq(null), eq(null), anyList(), any(), anyList()))
                .thenReturn(proposalResponse);
        when(schedulePreviewService.computePreview(eq(USER_ID), eq(PROPOSAL_ID), any()))
                .thenReturn(SchedulePreviewResponse.builder().proposalId(PROPOSAL_ID).build());

        PlanningDraftResponse draft = service.createDraft(USER_ID, RECOMMENDATION_ID, null);

        assertThat(draft.getProposalId()).isEqualTo(PROPOSAL_ID);
        assertThat(draft.getTopicId()).isEqualTo(TOPIC_ID);
        verify(studyRecommendationMapper).attachProposal(RECOMMENDATION_ID, PROPOSAL_ID, RecommendationStatus.SENT_TO_PLANNING.name());
        // Apply 전에는 실제 실행 일정이 절대 변경되면 안 된다.
        verifyNoInteractions(executionItemService);
    }

    @Test
    void apply_linksTopicToCreatedExecutionItem_andMarksRecommendationPlanned() {
        when(studyRecommendationMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(suggestedRecommendation());

        // apply() 호출 시점엔 아직 UNSCHEDULED 상태인 원본 제안 항목(호출자가 편집을 안 보낸 상황).
        AiProposalItemResponse unscheduledItem = AiProposalItemResponse.builder()
                .proposalItemId(1L).status(AiProposalItemStatus.PROPOSED)
                .title("이중 연결 리스트 학습").expectedMinutes(45).priority("SHOULD").build();
        when(aiProposalService.get(PROPOSAL_ID, USER_ID)).thenReturn(
                AiProposalResponse.builder().proposalId(PROPOSAL_ID).items(List.of(unscheduledItem)).build());

        // 저장된 스케줄 미리보기가 이 항목을 특정 시각에 배치해둔 상태.
        com.jungwoo.project.memo.scheduling.dto.PlacedItemDto placed =
                com.jungwoo.project.memo.scheduling.dto.PlacedItemDto.builder()
                        .proposalItemId(1L)
                        .scheduledDate(java.time.LocalDate.of(2026, 8, 9))
                        .scheduledStartAt(java.time.LocalDateTime.of(2026, 8, 9, 10, 0))
                        .scheduledEndAt(java.time.LocalDateTime.of(2026, 8, 9, 10, 45))
                        .build();
        when(schedulePreviewService.getStoredPreview(USER_ID, PROPOSAL_ID)).thenReturn(
                SchedulePreviewResponse.builder().proposalId(PROPOSAL_ID).placedItems(List.of(placed)).build());

        AiProposalItemResponse createdItem = AiProposalItemResponse.builder()
                .proposalItemId(1L).status(AiProposalItemStatus.APPLIED).createdItemId(555L).build();
        AiProposalResponse applied = AiProposalResponse.builder()
                .proposalId(PROPOSAL_ID).status(AiProposalStatus.APPLIED).items(List.of(createdItem)).build();
        AiProposalApplyRequest request = AiProposalApplyRequest.builder().build();
        when(aiProposalService.apply(eq(PROPOSAL_ID), eq(USER_ID), any())).thenReturn(applied);

        AiProposalResponse result = service.apply(USER_ID, PROPOSAL_ID, request);

        assertThat(result).isSameAs(applied);
        verify(executionItemService).linkTopic(555L, USER_ID, TOPIC_ID);
        verify(studyRecommendationMapper).updateStatus(RECOMMENDATION_ID, RecommendationStatus.PLANNED.name());

        // Planning Agent가 저장된 배치 결과로 UNSCHEDULED -> TIME_FIXED 편집을 직접 만들어 보냈는지.
        ArgumentCaptor<AiProposalApplyRequest> requestCaptor = ArgumentCaptor.forClass(AiProposalApplyRequest.class);
        verify(aiProposalService).apply(eq(PROPOSAL_ID), eq(USER_ID), requestCaptor.capture());
        AiProposalApplyRequest.EditedProposalItem edit = requestCaptor.getValue().getEditedItems().get(0);
        assertThat(edit.getPlacementType()).isEqualTo(com.jungwoo.project.memo.execution.domain.PlacementType.TIME_FIXED);
        assertThat(edit.getScheduledStartAt()).isEqualTo(java.time.LocalDateTime.of(2026, 8, 9, 10, 0));
    }

    @Test
    void apply_skipsTopicLinking_whenProposalDidNotComeFromPlanningAgent() {
        when(studyRecommendationMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(null);

        AiProposalItemResponse createdItem = AiProposalItemResponse.builder()
                .proposalItemId(1L).status(AiProposalItemStatus.APPLIED).createdItemId(555L).build();
        AiProposalResponse applied = AiProposalResponse.builder()
                .proposalId(PROPOSAL_ID).status(AiProposalStatus.APPLIED).items(List.of(createdItem)).build();
        AiProposalApplyRequest request = AiProposalApplyRequest.builder().build();
        when(aiProposalService.apply(PROPOSAL_ID, USER_ID, request)).thenReturn(applied);

        service.apply(USER_ID, PROPOSAL_ID, request);

        verify(executionItemService, never()).linkTopic(any(), any(), any());
        verify(studyRecommendationMapper, never()).updateStatus(any(), any());
    }
}
