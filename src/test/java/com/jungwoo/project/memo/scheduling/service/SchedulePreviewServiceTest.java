package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.scheduling.SchedulePreviewMapper;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.solver.SchedulingSolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SchedulePreviewService는 AiConsultationClient/AiConversationService를 의존성으로 갖지
 * 않는다 — 이 클래스가 컴파일된다는 사실 자체가 "재계산이 OpenAI를 호출하지 않는다"는 계약을
 * 구조적으로 보장한다. Clock을 2026-08-10(월) 09:00 Asia/Seoul로 고정해 날짜 관련 검증이
 * 실행 시각에 흔들리지 않게 한다.
 */
@ExtendWith(MockitoExtension.class)
class SchedulePreviewServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long PROPOSAL_ID = 100L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    @Mock
    private AiProposalMapper aiProposalMapper;
    @Mock
    private AiProposalItemMapper aiProposalItemMapper;
    @Mock
    private SchedulePreviewMapper schedulePreviewMapper;
    @Mock
    private ExecutionItemMapper executionItemMapper;
    @Mock
    private RoutineOccurrenceService routineOccurrenceService;
    @Mock
    private CommitmentService commitmentService;

    private SchedulePreviewService service;

    @BeforeEach
    void setUp() {
        AvailabilityEstimateService availabilityEstimateService =
                new AvailabilityEstimateService(
                        executionItemMapper, routineOccurrenceService, commitmentService, FIXED_CLOCK);
        SchedulingSolverService solverService = new SchedulingSolverService(3);
        service = new SchedulePreviewService(
                aiProposalMapper, aiProposalItemMapper, schedulePreviewMapper,
                availabilityEstimateService, solverService, FIXED_CLOCK);
    }

    private AiProposal proposal() {
        return AiProposal.builder()
                .proposalId(PROPOSAL_ID).userId(USER_ID)
                .status(AiProposalStatus.PROPOSED)
                .targetScope(AiProposalTargetScope.TODAY)
                .unavailableWindows(null)
                .build();
    }

    private AiProposalItem proposalItem(long id, String title, int expectedMinutes, String priority,
                                         LocalDate deadlineDate) {
        String json = """
                {"title":"%s","description":null,"expectedMinutes":%d,"priority":"%s",
                "targetDate":"2026-08-10","placementType":"UNSCHEDULED",
                "scheduledStartAt":null,"scheduledEndAt":null,
                "earliestStartDate":null,"deadlineDate":%s}
                """.formatted(title, expectedMinutes, priority,
                deadlineDate != null ? "\"" + deadlineDate + "\"" : "null");
        return AiProposalItem.builder()
                .proposalItemId(id).proposalId(PROPOSAL_ID).userId(USER_ID)
                .itemType(AiProposalItemType.EXECUTION_ITEM)
                .originalPayload(json)
                .status(AiProposalItemStatus.PROPOSED)
                .build();
    }

    @Test
    void computePreview_deniesAccess_whenProposalNotOwnedByCurrentUser() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, OTHER_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.computePreview(OTHER_USER_ID, PROPOSAL_ID, SchedulePreviewRequest.builder().build()))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_PROPOSAL_NOT_FOUND));

        verify(schedulePreviewMapper, never()).insert(any());
    }

    @Test
    void getStoredPreview_deniesAccess_whenProposalNotOwnedByCurrentUser() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, OTHER_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getStoredPreview(OTHER_USER_ID, PROPOSAL_ID))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_PROPOSAL_NOT_FOUND));
    }

    @Test
    void placesAllCandidates_whenPlentyOfAvailableTime() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(proposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(List.of(
                proposalItem(1L, "강의 1", 30, "MUST", null),
                proposalItem(2L, "강의 2", 30, "SHOULD", null)
        ));
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        SchedulePreviewResponse response = service.computePreview(USER_ID, PROPOSAL_ID,
                SchedulePreviewRequest.builder().horizonStart(TODAY).horizonEnd(TODAY.plusDays(6)).build());

        assertThat(response.getUnplacedItems()).isEmpty();
        assertThat(response.getPlacedItems()).hasSize(2);
        assertThat(response.getPlacedItems()).allSatisfy(p -> {
            assertThat(p.getPlacementType()).isEqualTo(PlacementType.TIME_FIXED);
            assertThat(p.getScheduledStartAt()).isAfterOrEqualTo(java.time.LocalDateTime.of(2026, 8, 10, 9, 0));
        });
        verify(schedulePreviewMapper, times(1)).insert(any());
    }

    @Test
    void leavesLowerPriorityUnplaced_whenNotEnoughAvailableTime() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(proposal());

        // 하루 안에만 배치 가능하도록 짧은 horizon(오늘 하루)과, 그 하루의 기본 추정 창(19:00-22:00,
        // 3시간)을 거의 다 채우는 4개의 90분짜리 후보를 넣어 시간을 의도적으로 부족하게 만든다.
        List<AiProposalItem> items = new ArrayList<>();
        items.add(proposalItem(1L, "A(MUST)", 90, "MUST", null));
        items.add(proposalItem(2L, "B(MUST)", 90, "MUST", null));
        items.add(proposalItem(3L, "C(OPTIONAL)", 90, "OPTIONAL", null));
        items.add(proposalItem(4L, "D(OPTIONAL)", 90, "OPTIONAL", null));
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(items);
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        SchedulePreviewResponse response = service.computePreview(USER_ID, PROPOSAL_ID,
                SchedulePreviewRequest.builder().horizonStart(TODAY).horizonEnd(TODAY).build());

        // 3시간(180분) 창에는 90분짜리 2개까지만 겹치지 않게 들어간다 - 최소 2개는 미배치.
        assertThat(response.getUnplacedItems().size()).isGreaterThanOrEqualTo(2);
        // 오류가 아니라 정해진 사유로 남는다.
        assertThat(response.getUnplacedItems()).allSatisfy(u -> assertThat(u.getReason()).isNotBlank());
        // MUST 항목이 OPTIONAL보다 우선 배치된다.
        List<Long> placedIds = response.getPlacedItems().stream().map(p -> p.getProposalItemId()).toList();
        assertThat(placedIds).contains(1L, 2L);
    }

    @Test
    void doesNotPlaceEitherItem_whenDeadlineIsInThePast() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(proposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, "마감 지난 후보", 30, "MUST", TODAY.minusDays(1))));
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        SchedulePreviewResponse response = service.computePreview(USER_ID, PROPOSAL_ID,
                SchedulePreviewRequest.builder().horizonStart(TODAY).horizonEnd(TODAY.plusDays(6)).build());

        assertThat(response.getPlacedItems()).isEmpty();
        assertThat(response.getUnplacedItems()).hasSize(1);
    }

    @Test
    void restoresStoredPreview_afterRecompute() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(proposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, "강의", 30, "SHOULD", null)));
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        service.computePreview(USER_ID, PROPOSAL_ID,
                SchedulePreviewRequest.builder().horizonStart(TODAY).horizonEnd(TODAY.plusDays(6)).build());

        verify(schedulePreviewMapper).insert(any());
        // update 경로(이미 존재)도 별도로 확인 — findByProposalIdAndUserId가 null이 아니면 update를 쓴다.
        when(schedulePreviewMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(com.jungwoo.project.memo.scheduling.domain.AiProposalSchedulePreview.builder()
                        .schedulePreviewId(1L).proposalId(PROPOSAL_ID).userId(USER_ID).build());

        service.computePreview(USER_ID, PROPOSAL_ID,
                SchedulePreviewRequest.builder().horizonStart(TODAY).horizonEnd(TODAY.plusDays(6)).build());

        verify(schedulePreviewMapper, times(1)).update(any());
    }
}
