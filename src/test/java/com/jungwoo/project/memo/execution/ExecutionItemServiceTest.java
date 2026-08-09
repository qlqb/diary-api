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
        when(executionItemMapper.updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L), eq("줄인 제목"), isNull())).thenReturn(1);

        service.reduce(ITEM_ID, USER_ID, ExecutionItemReduceRequest.builder()
                .reducedTitle("줄인 제목").version(0L).build());

        verify(executionItemMapper).updateForReduce(eq(ITEM_ID), eq(USER_ID), eq(0L), eq("줄인 제목"), isNull());
    }

    @Test
    void move_updatesItem_andWritesMovedEvent_together() {
        ExecutionItem item = plannedItem(0L);
        when(executionItemMapper.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(item);
        when(executionItemMapper.updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), any(), any())).thenReturn(1);

        service.move(ITEM_ID, USER_ID, ExecutionItemMoveRequest.builder()
                .toDate(DATE.plusDays(1)).version(0L).build());

        verify(executionItemMapper).updateForMove(eq(ITEM_ID), eq(USER_ID), eq(0L),
                eq(DATE.plusDays(1)), any(), any());
        verify(executionItemEventMapper).insert(argThat(event ->
                event.getEventType() == ExecutionEventType.MOVED
                        && event.getExecutionItemId().equals(ITEM_ID)));
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

        verify(executionItemMapper, never()).updateForReduce(any(), any(), any(), any(), any());
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
