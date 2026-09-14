package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.execution.domain.ExecutionEventType;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionItemCompletedEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResumeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionItemServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);

    @Mock
    private ExecutionItemMapper executionItemMapper;

    @Mock
    private ExecutionItemEventMapper executionItemEventMapper;

    @Mock
    private ExecutionRecordMapper executionRecordMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ExecutionItemService service;

    @Test
    void complete_deniesAccess_whenItemNotOwnedByCurrentUser() {
        // findByIdAndUserId already scopes by user_id — 다른 사용자 소유 항목이면 null이 반환된다
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.complete(ITEM_ID, USER_ID,
                ExecutionItemCompleteRequest.builder().version(0L).build()))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_ITEM_NOT_FOUND));

        verify(executionRecordMapper, never()).insert(any());
    }

    @Test
    void complete_throwsVersionConflict_whenRequestVersionIsStale() {
        ExecutionItem item = plannedItem(1L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.complete(ITEM_ID, USER_ID,
                ExecutionItemCompleteRequest.builder().version(0L).build()))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        verify(executionItemMapper, never()).completeWithVersion(any(), any(), any());
    }

    @Test
    void complete_createsCompletedRecord_andDoneStatus_together() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.completeWithVersion(ITEM_ID, USER_ID, 0L)).thenReturn(1);

        service.complete(ITEM_ID, USER_ID, ExecutionItemCompleteRequest.builder()
                .version(0L).actualMinutes(30).build());

        verify(executionItemMapper).completeWithVersion(ITEM_ID, USER_ID, 0L);
        verify(executionRecordMapper).insert(argThat(record ->
                record.getExecutionItemId().equals(ITEM_ID)
                        && record.getOutcome() == ExecutionRecordOutcome.COMPLETED
                        && record.getCompletionPercent() == 100));
    }

    @Test
    void complete_publishesLearningFeedbackEvent_whenItemLinkedToTopic() {
        ExecutionItem item = plannedItem(0L);
        item.setTopicId(77L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.completeWithVersion(ITEM_ID, USER_ID, 0L)).thenReturn(1);

        service.complete(ITEM_ID, USER_ID, ExecutionItemCompleteRequest.builder().version(0L).build());

        verify(eventPublisher).publishEvent(argThat((ExecutionItemCompletedEvent event) ->
                event.executionItemId().equals(ITEM_ID)
                        && event.userId().equals(USER_ID)
                        && event.topicId().equals(77L)));
    }

    @Test
    void complete_doesNotPublishLearningFeedbackEvent_whenItemHasNoTopic() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.completeWithVersion(ITEM_ID, USER_ID, 0L)).thenReturn(1);

        service.complete(ITEM_ID, USER_ID, ExecutionItemCompleteRequest.builder().version(0L).build());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void complete_fails_whenItemIsOnHold_mustResumeFirst() {
        ExecutionItem item = holdItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.complete(ITEM_ID, USER_ID,
                ExecutionItemCompleteRequest.builder().version(0L).build()))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        verify(executionItemMapper, never()).completeWithVersion(any(), any(), any());
    }

    @Test
    void resume_transitionsHoldToPlanned_andWritesResumedEvent() {
        ExecutionItem item = holdItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateStatusWithVersion(ITEM_ID, USER_ID, 0L, ExecutionStatus.PLANNED)).thenReturn(1);

        service.resume(ITEM_ID, USER_ID, ExecutionItemResumeRequest.builder().version(0L).build());

        verify(executionItemMapper).updateStatusWithVersion(ITEM_ID, USER_ID, 0L, ExecutionStatus.PLANNED);
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.RESUMED
                        && event.getExecutionItemId().equals(ITEM_ID)));
    }

    @Test
    void resume_rejectsItem_whenNotOnHold() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.resume(ITEM_ID, USER_ID,
                ExecutionItemResumeRequest.builder().version(0L).build()))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        verify(executionItemMapper, never()).updateStatusWithVersion(any(), any(), any(), any());
    }

    @Test
    void hold_transitionsPlannedToHold() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateStatusWithVersion(ITEM_ID, USER_ID, 0L, ExecutionStatus.HOLD)).thenReturn(1);

        service.hold(ITEM_ID, USER_ID, ExecutionItemHoldRequest.builder().version(0L).build());

        verify(executionItemMapper).updateStatusWithVersion(ITEM_ID, USER_ID, 0L, ExecutionStatus.HOLD);
    }

    @Test
    void complete_succeeds_forTimeFixedItem_placementTypeDoesNotBlockCompletion() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.completeWithVersion(ITEM_ID, USER_ID, 0L)).thenReturn(1);

        service.complete(ITEM_ID, USER_ID, ExecutionItemCompleteRequest.builder().version(0L).build());

        verify(executionItemMapper).completeWithVersion(ITEM_ID, USER_ID, 0L);
    }

    @Test
    void reduce_succeeds_forTimeFixedItem_placementTypeDoesNotBlockReduce() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L), eq("줄인 제목"), isNull(), isNull())).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .reducedTitle("줄인 제목").version(0L).build());

        // 제목만 바꾸는 요청은 시각과 expectedMinutes를 건드리지 않는다.
        verify(executionItemMapper).updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L), eq("줄인 제목"), isNull(), isNull());
    }

    @Test
    void move_updatesItem_andWritesMovedEvent_together() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), any(), any(), any())).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE.plusDays(1)).version(0L).build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), any(), any(), any());
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.MOVED
                        && event.getExecutionItemId().equals(ITEM_ID)));
    }

    @Test
    void move_shiftsTimeWithinSameDay_andStillWritesMovedEvent() {
        // "오늘 뒤로" — 밀린 항목을 같은 날 남은 시간대로 옮긴다. 날짜는 그대로여도 "언제 할지"가
        // 바뀌었으므로 이동이고, 새 이벤트 타입을 만들지 않고 MOVED로 남는다.
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(16, 0)), eq(DATE.atTime(16, 30)), eq(30))).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE)
                .startTime(LocalTime.of(16, 0))
                .endTime(LocalTime.of(16, 30))
                .version(0L)
                .build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(16, 0)), eq(DATE.atTime(16, 30)), eq(30));
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.MOVED
                        && event.getExecutionItemId().equals(ITEM_ID)));
    }

    @Test
    void move_rejectsRequest_whenNeitherDateNorTimeChanges() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(9, 30))
                .version(0L)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MOVE_TARGET_DATE_INVALID));

        verify(executionItemMapper, never()).updateForMove(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void move_rejectsTimeShift_forItemWithoutFixedTime() {
        // 시각 없는 항목에 시각을 붙이는 것은 이동이 아니라 배치 형식 변경이다.
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE)
                .startTime(LocalTime.of(16, 0))
                .endTime(LocalTime.of(16, 30))
                .version(0L)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_MUST_NOT_HAVE_TIME));

        verify(executionItemMapper, never()).updateForMove(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void move_rejectsRequest_whenOnlyOneSideOfTimeRangeIsGiven() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE).startTime(LocalTime.of(16, 0)).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARTIAL_TIME_RANGE));
    }

    @Test
    void move_toAnotherDate_withoutTimes_keepsShiftingTimeByDayDifference() {
        // 기존 동작 회귀 방지: 시각을 주지 않으면 예전처럼 날짜 차이만큼 평행 이동한다.
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), eq(DATE.plusDays(1).atTime(9, 0)),
                eq(DATE.plusDays(1).atTime(9, 30)), eq(30))).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE.plusDays(1)).version(0L).build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), eq(DATE.plusDays(1).atTime(9, 0)), eq(DATE.plusDays(1).atTime(9, 30)), eq(30));
    }

    @Test
    void reduce_rejectsRequest_whenNothingActuallyChanges() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .reducedTitle(item.getTitle())
                .version(0L)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_ITEM_NO_ACTUAL_CHANGE));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    /*
       ===== TIME_FIXED의 길이는 시각이 정한다 =====

       expectedMinutes와 (시작, 종료) 구간은 같은 사실을 가리키는 두 출처다. 시각이 진실이고
       길이는 파생값이라, 두 값이 어긋난 행이 만들어질 수 있으면 데이터 계약이 깨진 것이다.
       실제로 17:00~23:00짜리 알바가 120분으로 저장된 적이 있다.
     */

    @Test
    void create_derivesMinutesFromTheSpan_forTimeFixed_ignoringTheRequestedValue() {
        ExecutionItemCreateRequest request = ExecutionItemCreateRequest.builder()
                .title("알바 근무")
                .scheduledDate(DATE)
                .scheduledStartAt(DATE.atTime(17, 0))
                .scheduledEndAt(DATE.atTime(23, 0))
                .expectedMinutes(120)   // 요청이 뭘 보내든 시각이 이긴다
                .build();
        when(executionItemMapper.findByIdAndUserId(any(), eq(USER_ID))).thenReturn(timeFixedItem(0L));

        service.create(USER_ID, request);

        verify(executionItemMapper).insert(argThat(item ->
                item.getPlacementType() == PlacementType.TIME_FIXED
                        && item.getExpectedMinutes() == 360));
    }

    @Test
    void create_keepsRequestedMinutes_forDateOnly() {
        // 시각이 없는 항목은 길이를 사람이 정한다 — 여기까지 덮어쓰면 안 된다.
        ExecutionItemCreateRequest request = ExecutionItemCreateRequest.builder()
                .title("알고리즘 문제 풀기")
                .scheduledDate(DATE)
                .expectedMinutes(45)
                .build();
        when(executionItemMapper.findByIdAndUserId(any(), eq(USER_ID))).thenReturn(plannedItem(0L));

        service.create(USER_ID, request);

        verify(executionItemMapper).insert(argThat(item ->
                item.getPlacementType() == PlacementType.DATE_ONLY
                        && item.getExpectedMinutes() == 45));
    }

    @Test
    void createFromApprovedProposal_derivesMinutesFromTheSpan_forTimeFixed() {
        // AI 제안 적용도 같은 경계를 지난다. 제안 payload가 어떤 값을 들고 오든 시각이 이긴다 —
        // 17:00~23:00짜리 알바가 120분으로 저장된 사고가 바로 이 지점이었다.
        service.createFromApprovedProposal(USER_ID, "알바 근무", null, DATE,
                120, ExecutionPriority.MUST, 0, false,
                PlacementType.TIME_FIXED, DATE.atTime(17, 0), DATE.atTime(23, 0));

        verify(executionItemMapper).insert(argThat(item -> item.getExpectedMinutes() == 360));
    }

    @Test
    void createFromApprovedProposal_keepsGivenMinutes_forUnscheduled() {
        // 시각이 없는 계획 항목은 솔버가 이 길이를 보고 슬롯을 고른다 — 덮어쓰면 안 된다.
        service.createFromApprovedProposal(USER_ID, "자료구조 3장 읽기", null, null,
                45, ExecutionPriority.SHOULD, 0, false,
                PlacementType.UNSCHEDULED, null, null);

        verify(executionItemMapper).insert(argThat(item -> item.getExpectedMinutes() == 45));
    }

    @Test
    void move_recomputesMinutes_whenTheSpanChanges() {
        // 09:00~09:30(30분)을 17:00~23:00으로 옮기면 360분이 된다. 시각만 갱신하고
        // expectedMinutes를 두면 6시간짜리가 30분으로 남는다.
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(17, 0)), eq(DATE.atTime(23, 0)), eq(360))).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE)
                .startTime(LocalTime.of(17, 0))
                .endTime(LocalTime.of(23, 0))
                .version(0L)
                .build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(17, 0)), eq(DATE.atTime(23, 0)), eq(360));
    }

    @Test
    void move_leavesMinutesAlone_forDateOnly() {
        // 시각이 없으면 이동으로 길이가 달라질 수 없다 — null을 넘겨 기존 값을 보존한다.
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), isNull(), isNull(), isNull())).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE.plusDays(1)).version(0L).build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), isNull(), isNull(), isNull());
    }

    @Test
    void reduce_shortensTheEndTime_forTimeFixed_keepingTheStart() {
        // 09:00~09:30(30분)을 10분으로 줄이면 09:00~09:10이 된다. 시작 시각은 그대로다 —
        // 사용자가 줄이려는 것은 분량이지 언제 시작할지가 아니다.
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                isNull(), eq(10), eq(DATE.atTime(9, 10)))).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(10).version(0L).build());

        verify(executionItemMapper).updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                isNull(), eq(10), eq(DATE.atTime(9, 10)));
    }

    @Test
    void reduce_writesBothMinutesAndEndTime_intoTheReducedEvent() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForReduce(any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(10).version(0L).build());

        /*
           시각이 함께 바뀐 변경이므로 무엇이 달라졌는지 이벤트만 보고 알 수 있어야 한다.

           scheduledEndAt의 직렬화 형식에는 기대지 않는다 — 이 서비스의 ObjectMapper는
           LocalDateTime을 배열로 쓰고(WRITE_DATES_AS_TIMESTAMPS 기본값), 같은 컬럼에
           String.valueOf로 문자열을 넣는 호출부도 따로 있다. 여기서 검증할 것은 "종료 시각의
           전후가 남는가"이지 그 표기법이 아니다.
         */
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.REDUCED
                        && event.getBeforeState().contains("scheduledEndAt")
                        && event.getBeforeState().contains("\"expectedMinutes\":30")
                        && event.getAfterState().contains("scheduledEndAt")
                        && event.getAfterState().contains("\"expectedMinutes\":10")
                        && !event.getBeforeState().equals(event.getAfterState())));
    }

    @Test
    void reduce_rejectsSameLength_forTimeFixed() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(30).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REDUCE_MUST_SHORTEN));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_rejectsLonger_forTimeFixed_thatIsMoveNotReduce() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(90).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REDUCE_MUST_SHORTEN));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_rejectsSameLength_forDateOnly() {
        // 축소 검사는 배치 형식과 무관하다 — 예전에는 TIME_FIXED에만 걸려 있었다.
        ExecutionItem item = plannedItem(0L);   // expectedMinutes = 30
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(30).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REDUCE_MUST_SHORTEN));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_rejectsLonger_forDateOnly() {
        // 30분짜리에 90분을 보내면 늘어난 값이 REDUCED 이벤트로 남았다. 그건 줄이기가 아니다.
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(90).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REDUCE_MUST_SHORTEN));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_rejectsMinutes_whenCurrentMinutesIsNull() {
        // 지금 길이를 모르면 무엇에 견줘 줄이는지도 알 수 없다.
        ExecutionItem item = plannedItem(0L);
        item.setExpectedMinutes(null);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(10).version(0L).build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REDUCE_MUST_SHORTEN));

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_allowsTitleOnly_whenCurrentMinutesIsNull() {
        // 길이를 모르는 것과 제목을 줄이는 것은 별개다 — 후자는 계속 된다.
        ExecutionItem item = plannedItem(0L);
        item.setExpectedMinutes(null);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq("줄인 제목"), isNull(), isNull())).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .reducedTitle("줄인 제목").version(0L).build());

        verify(executionItemMapper).updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq("줄인 제목"), isNull(), isNull());
    }

    // ===== 롤링 배치도 같은 정책을 지난다 =====

    @Test
    void applyRollingPlacement_derivesMinutesFromPlacedSpan() {
        /*
           솔버가 만든 구간은 보통 expectedMinutes에서 나오므로 둘이 우연히 일치한다. 하지만
           expectedMinutes가 null인 항목은 PlanPlacementService가 30분으로 가정해 구간을
           만들고, 그러면 길이가 null인 TIME_FIXED가 남는다. 구간이 길이를 정해야 한다.
         */
        ExecutionItem item = plannedItem(0L);
        item.setPlacementType(PlacementType.UNSCHEDULED);
        item.setExpectedMinutes(45);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.applyTimeFixedPlacement(eq(USER_ID), eq(ITEM_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(10, 0)), eq(DATE.atTime(11, 0)), eq(60))).thenReturn(1);

        service.applyRollingPlacement(USER_ID, ITEM_ID, DATE,
                DATE.atTime(10, 0), DATE.atTime(11, 0));

        // 기존 45가 아니라 배치된 구간 60이 저장돼야 한다.
        verify(executionItemMapper).applyTimeFixedPlacement(eq(USER_ID), eq(ITEM_ID), eq(0L),
                eq(DATE), eq(DATE.atTime(10, 0)), eq(DATE.atTime(11, 0)), eq(60));
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.MOVED
                        && event.getAfterState().contains("\"expectedMinutes\":60")));
    }

    @Test
    void applyRollingPlacement_rejectsInvalidSpan() {
        ExecutionItem item = plannedItem(0L);
        item.setPlacementType(PlacementType.UNSCHEDULED);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.applyRollingPlacement(USER_ID, ITEM_ID, DATE,
                DATE.atTime(11, 0), DATE.atTime(10, 0)))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));

        verify(executionItemMapper, never()).applyTimeFixedPlacement(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ===== 시각은 분 단위여야 한다 =====

    @Test
    void create_rejectsSubMinuteTimeFixedSpan() {
        /*
           09:00:30~09:01:00은 end > start를 통과하지만 길이가 0분으로 잘린다. 0은
           chk_execution_items_expected_minutes(> 0)에 걸려 저장이 터지므로, 500이 나가기 전에
           400으로 막는다.
         */
        ExecutionItemCreateRequest request = ExecutionItemCreateRequest.builder()
                .title("짧은 일")
                .scheduledDate(DATE)
                .scheduledStartAt(DATE.atTime(9, 0, 30))
                .scheduledEndAt(DATE.atTime(9, 1))
                .build();

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));

        verify(executionItemMapper, never()).insert(any());
    }

    @Test
    void move_rejectsTimesContainingSeconds() {
        ExecutionItem item = timeFixedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);

        assertThatThrownBy(() -> service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE)
                .startTime(LocalTime.of(16, 0, 30))
                .endTime(LocalTime.of(16, 30))
                .version(0L)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));

        verify(executionItemMapper, never()).updateForMove(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reduce_doesNotTouchTimes_forDateOnly() {
        // 시각이 없는 항목은 예전 그대로 expectedMinutes만 바뀐다.
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                isNull(), eq(10), isNull())).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .expectedMinutes(10).version(0L).build());

        verify(executionItemMapper).updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L),
                isNull(), eq(10), isNull());
    }

    @Test
    void delete_softDeletes_andWritesDeletedEvent_together() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.softDeleteWithVersion(ITEM_ID, USER_ID, 0L)).thenReturn(1);

        service.delete(ITEM_ID, USER_ID, 0L);

        verify(executionItemMapper).softDeleteWithVersion(ITEM_ID, USER_ID, 0L);
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.DELETED
                        && event.getExecutionItemId().equals(ITEM_ID)));
    }

    @Test
    void restore_undoesSoftDelete_andWritesRestoredEvent_together() {
        ExecutionItem deleted = plannedItem(1L);
        // 삭제된 항목은 findByIdAndUserId로 찾을 수 없다 — 되돌리기 전용 조회를 써야 한다.
        when(executionItemMapper.findByIdAndUserIdIncludingDeleted(ITEM_ID, USER_ID)).thenReturn(deleted);
        when(executionItemMapper.restoreWithVersion(ITEM_ID, USER_ID, 1L)).thenReturn(1);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(plannedItem(2L));

        service.restore(ITEM_ID, USER_ID, 1L);

        verify(executionItemMapper).restoreWithVersion(ITEM_ID, USER_ID, 1L);
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.RESTORED
                        && event.getExecutionItemId().equals(ITEM_ID)));
    }

    @Test
    void restore_rejects_whenNothingWasDeletedOrVersionMovedOn() {
        ExecutionItem deleted = plannedItem(1L);
        when(executionItemMapper.findByIdAndUserIdIncludingDeleted(ITEM_ID, USER_ID)).thenReturn(deleted);
        // is_deleted = 1 조건에 걸리지 않으면 0행이 바뀐다 — 이미 살아 있거나 버전이 어긋난 것이다.
        when(executionItemMapper.restoreWithVersion(ITEM_ID, USER_ID, 1L)).thenReturn(0);

        assertThatThrownBy(() -> service.restore(ITEM_ID, USER_ID, 1L))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        verify(executionItemEventMapper, never()).insert(any());
    }

    @Test
    void getByDateRange_mapsMapperResultsToResponses_inRangeOrder() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByUserIdAndDateRange(USER_ID, DATE, DATE.plusDays(6)))
                .thenReturn(java.util.List.of(item));

        var result = service.getByDateRange(USER_ID, DATE, DATE.plusDays(6));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExecutionItemId()).isEqualTo(ITEM_ID);
    }

    @Test
    void getByDateRange_rejectsEndDateBeforeStartDate() {
        assertThatThrownBy(() -> service.getByDateRange(USER_ID, DATE, DATE.minusDays(1)))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(executionItemMapper, never()).findByUserIdAndDateRange(any(), any(), any());
    }

    private ExecutionItem plannedItem(Long version) {
        return ExecutionItem.builder()
                .executionItemId(ITEM_ID)
                .userId(USER_ID)
                .title("알고리즘 문제 풀기")
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(DATE)
                .expectedMinutes(30)
                .status(ExecutionStatus.PLANNED)
                .priority(ExecutionPriority.SHOULD)
                .orderIndex(0)
                .originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .version(version)
                .isDeleted(false)
                .build();
    }

    private ExecutionItem holdItem(Long version) {
        ExecutionItem item = plannedItem(version);
        item.setStatus(ExecutionStatus.HOLD);
        return item;
    }

    /**
     * TIME_FIXED(시작·종료 시각이 정해진 배치 형식)는 isFixed(이동·축소·보류 등을 제한하는
     * 잠금 속성)와 다른 개념이다 — 이 항목은 완료·축소가 막히면 안 된다.
     */
    private ExecutionItem timeFixedItem(Long version) {
        ExecutionItem item = plannedItem(version);
        item.setPlacementType(PlacementType.TIME_FIXED);
        item.setScheduledStartAt(DATE.atTime(9, 0));
        item.setScheduledEndAt(DATE.atTime(9, 30));
        return item;
    }
}
