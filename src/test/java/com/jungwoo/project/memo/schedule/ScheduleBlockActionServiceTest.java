package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.dailyplan.DailyPlanService;
import com.jungwoo.project.memo.planitem.PlanItemEventMapper;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import com.jungwoo.project.memo.schedule.domain.ScheduleOriginType;
import com.jungwoo.project.memo.schedule.domain.ScheduleStatus;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockReduceRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockReduceTimeMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleBlockActionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SCHEDULE_BLOCK_ID = 10L;
    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 9, 19, 0);
    private static final LocalDateTime END_TIME = LocalDateTime.of(2026, 7, 9, 21, 0);

    @Mock
    private ScheduleBlockMapper scheduleBlockMapper;

    @Mock
    private PlanItemEventMapper planItemEventMapper;

    @Mock
    private DailyPlanService dailyPlanService;

    @InjectMocks
    private ScheduleBlockActionService service;

    @Test
    void reduce_keepSucceedsWhenTitleChanges() {
        ScheduleBlock block = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, END_TIME);
        ScheduleBlock updated = timeFixedBlock("알고리즘 1문제 풀기", START_TIME, END_TIME);
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block, updated);

        service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 1문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.KEEP)
                .build());

        verify(scheduleBlockMapper).updateForReduce(
                SCHEDULE_BLOCK_ID,
                USER_ID,
                "알고리즘 1문제 풀기",
                ScheduleBlockType.TIME_FIXED,
                START_TIME,
                END_TIME
        );
    }

    @Test
    void reduce_keepFailsWhenTitleIsSame() {
        ScheduleBlock block = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, END_TIME);
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block);

        assertThatThrownBy(() -> service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 2문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.KEEP)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.REDUCE_TITLE_UNCHANGED));
    }

    @Test
    void reduce_shrinkSucceedsWhenTitleIsSameAndTimeChanges() {
        LocalDateTime reducedEndTime = LocalDateTime.of(2026, 7, 9, 19, 30);
        ScheduleBlock block = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, END_TIME);
        ScheduleBlock updated = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, reducedEndTime);
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block, updated);

        service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 2문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.SHRINK)
                .blockType(ScheduleBlockType.TIME_FIXED)
                .startTime(START_TIME)
                .endTime(reducedEndTime)
                .build());

        verify(scheduleBlockMapper).updateForReduce(
                SCHEDULE_BLOCK_ID,
                USER_ID,
                "알고리즘 2문제 풀기",
                ScheduleBlockType.TIME_FIXED,
                START_TIME,
                reducedEndTime
        );
    }

    @Test
    void reduce_shrinkFailsWhenTitleAndTimeAreSame() {
        ScheduleBlock block = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, END_TIME);
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block);

        assertThatThrownBy(() -> service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 2문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.SHRINK)
                .blockType(ScheduleBlockType.TIME_FIXED)
                .startTime(START_TIME)
                .endTime(END_TIME)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.REDUCE_TITLE_UNCHANGED));
    }

    @Test
    void reduce_clearSucceedsWhenTitleIsSameAndTimeIsCleared() {
        ScheduleBlock block = timeFixedBlock("알고리즘 2문제 풀기", START_TIME, END_TIME);
        ScheduleBlock updated = taskBlock("알고리즘 2문제 풀기");
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block, updated);

        service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 2문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.CLEAR)
                .blockType(ScheduleBlockType.TASK)
                .build());

        verify(scheduleBlockMapper).updateForReduce(
                SCHEDULE_BLOCK_ID,
                USER_ID,
                "알고리즘 2문제 풀기",
                ScheduleBlockType.TASK,
                null,
                null
        );
    }

    @Test
    void reduce_clearFailsWhenBlockAlreadyHasNoTime() {
        ScheduleBlock block = taskBlock("알고리즘 2문제 풀기");
        when(scheduleBlockMapper.findByIdAndUserId(SCHEDULE_BLOCK_ID, USER_ID))
                .thenReturn(block);

        assertThatThrownBy(() -> service.reduce(SCHEDULE_BLOCK_ID, USER_ID, ScheduleBlockReduceRequest.builder()
                .reducedTitle("알고리즘 2문제 풀기")
                .timeMode(ScheduleBlockReduceTimeMode.CLEAR)
                .blockType(ScheduleBlockType.TASK)
                .build()))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.REDUCE_TITLE_UNCHANGED));
    }

    private ScheduleBlock timeFixedBlock(String title, LocalDateTime startTime, LocalDateTime endTime) {
        return baseBlock(title)
                .blockType(ScheduleBlockType.TIME_FIXED)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }

    private ScheduleBlock taskBlock(String title) {
        return baseBlock(title)
                .blockType(ScheduleBlockType.TASK)
                .startTime(null)
                .endTime(null)
                .build();
    }

    private ScheduleBlock.ScheduleBlockBuilder baseBlock(String title) {
        return ScheduleBlock.builder()
                .scheduleBlockId(SCHEDULE_BLOCK_ID)
                .userId(USER_ID)
                .blockDate(LocalDate.of(2026, 7, 9))
                .title(title)
                .status(ScheduleStatus.PLANNED)
                .originType(ScheduleOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .isDeleted(false);
    }
}
