package com.jungwoo.project.memo.scheduling.solver;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.domain.SchedulingContext;
import com.jungwoo.project.memo.scheduling.domain.SchedulingPlan;
import com.jungwoo.project.memo.scheduling.domain.SchedulingTask;
import com.jungwoo.project.memo.scheduling.domain.TimeSlotOption;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 7일 범위 일정 후보 배치의 강한 규칙과 핵심 선호 규칙을 개별적으로 검증한다. ConstraintVerifier는
 * 실제 Solver를 돌리지 않고 주어진 사실만으로 각 제약의 점수를 계산하므로 빠르고 결정적이다.
 */
class SchedulingConstraintProviderTest {

    private final ConstraintVerifier<SchedulingConstraintProvider, SchedulingPlan> verifier =
            ConstraintVerifier.build(new SchedulingConstraintProvider(), SchedulingPlan.class, SchedulingTask.class);

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 10, 9, 0);

    private SchedulingTask scheduledTask(long id, LocalDateTime start, int durationMinutes) {
        return scheduledTask(id, start, durationMinutes, AvailabilityConfidence.HIGH, ExecutionPriority.SHOULD, null);
    }

    private SchedulingTask scheduledTask(long id, LocalDateTime start, int durationMinutes,
                                          AvailabilityConfidence confidence, ExecutionPriority priority,
                                          LocalDateTime deadline) {
        SchedulingTask task = new SchedulingTask(id, "task-" + id, durationMinutes, priority, deadline, List.of());
        task.setAssignedSlot(new TimeSlotOption(start, confidence));
        return task;
    }

    private SchedulingTask unscheduledTask(long id, ExecutionPriority priority) {
        return new SchedulingTask(id, "task-" + id, 30, priority, null, List.of());
    }

    @Test
    void taskOverlapsAnotherTask_penalizesOverlappingPair() {
        SchedulingTask a = scheduledTask(1, BASE, 60);
        SchedulingTask b = scheduledTask(2, BASE.plusMinutes(30), 60);

        verifier.verifyThat(SchedulingConstraintProvider::taskOverlapsAnotherTask)
                .given(a, b)
                .penalizesBy(1);
    }

    @Test
    void taskOverlapsAnotherTask_doesNotPenalize_whenBackToBack() {
        SchedulingTask a = scheduledTask(1, BASE, 60);
        SchedulingTask b = scheduledTask(2, BASE.plusMinutes(60), 60);

        verifier.verifyThat(SchedulingConstraintProvider::taskOverlapsAnotherTask)
                .given(a, b)
                .penalizesBy(0);
    }

    @Test
    void taskOverlapsBusyWindow_penalizesFixedScheduleConflict() {
        SchedulingTask a = scheduledTask(1, BASE, 60);
        BusyWindow busy = new BusyWindow(BASE.plusMinutes(30), BASE.plusMinutes(90), "기존 일정");

        verifier.verifyThat(SchedulingConstraintProvider::taskOverlapsBusyWindow)
                .given(a, busy)
                .penalizesBy(1);
    }

    @Test
    void taskOverlapsBusyWindow_doesNotPenalize_whenNoOverlap() {
        SchedulingTask a = scheduledTask(1, BASE, 60);
        BusyWindow busy = new BusyWindow(BASE.plusHours(3), BASE.plusHours(4), "기존 일정");

        verifier.verifyThat(SchedulingConstraintProvider::taskOverlapsBusyWindow)
                .given(a, busy)
                .penalizesBy(0);
    }

    @Test
    void taskOutsideHorizon_penalizesTaskEndingAfterHorizon() {
        SchedulingContext context = new SchedulingContext(
                BASE.minusDays(1), BASE.minusDays(1), BASE.plusHours(2));
        SchedulingTask a = scheduledTask(1, BASE.plusHours(1), 120); // ends BASE+3h, horizon ends BASE+2h

        verifier.verifyThat(SchedulingConstraintProvider::taskOutsideHorizon)
                .given(a, context)
                .penalizesBy(1);
    }

    @Test
    void taskOutsideHorizon_doesNotPenalize_whenWithinHorizon() {
        SchedulingContext context = new SchedulingContext(
                BASE.minusDays(1), BASE.minusDays(1), BASE.plusDays(7));
        SchedulingTask a = scheduledTask(1, BASE, 60);

        verifier.verifyThat(SchedulingConstraintProvider::taskOutsideHorizon)
                .given(a, context)
                .penalizesBy(0);
    }

    @Test
    void taskStartsInPast_penalizesTaskBeforeNow() {
        SchedulingContext context = new SchedulingContext(BASE.plusHours(1), BASE.minusDays(1), BASE.plusDays(7));
        SchedulingTask a = scheduledTask(1, BASE, 30); // starts before "now"

        verifier.verifyThat(SchedulingConstraintProvider::taskStartsInPast)
                .given(a, context)
                .penalizesBy(1);
    }

    @Test
    void taskStartsInPast_doesNotPenalize_whenAfterNow() {
        SchedulingContext context = new SchedulingContext(BASE.minusHours(1), BASE.minusDays(1), BASE.plusDays(7));
        SchedulingTask a = scheduledTask(1, BASE, 30);

        verifier.verifyThat(SchedulingConstraintProvider::taskStartsInPast)
                .given(a, context)
                .penalizesBy(0);
    }

    @Test
    void taskPastDeadline_penalizesTaskEndingAfterDeadline() {
        SchedulingTask a = scheduledTask(1, BASE, 60, AvailabilityConfidence.HIGH,
                ExecutionPriority.MUST, BASE.plusMinutes(30));

        verifier.verifyThat(SchedulingConstraintProvider::taskPastDeadline)
                .given(a)
                .penalizesBy(1);
    }

    @Test
    void taskPastDeadline_doesNotPenalize_whenNoDeadline() {
        SchedulingTask a = scheduledTask(1, BASE, 60);

        verifier.verifyThat(SchedulingConstraintProvider::taskPastDeadline)
                .given(a)
                .penalizesBy(0);
    }

    @Test
    void preferHighPriorityScheduled_penalizesMustMoreThanOptional_whenUnscheduled() {
        SchedulingTask must = unscheduledTask(1, ExecutionPriority.MUST);
        SchedulingTask optional = unscheduledTask(2, ExecutionPriority.OPTIONAL);

        // MUST 미배치 페널티(5)가 OPTIONAL 미배치 페널티(1)보다 커야 한다 — 배치 시간이
        // 부족할 때 솔버가 MUST를 먼저 배치하도록 유도하는 신호다.
        verifier.verifyThat(SchedulingConstraintProvider::preferHighPriorityScheduled)
                .given(must)
                .penalizesBy(5);
        verifier.verifyThat(SchedulingConstraintProvider::preferHighPriorityScheduled)
                .given(optional)
                .penalizesBy(1);
    }

    @Test
    void preferHighPriorityScheduled_doesNotPenalize_whenScheduled() {
        SchedulingTask must = scheduledTask(1, BASE, 30, AvailabilityConfidence.HIGH, ExecutionPriority.MUST, null);

        verifier.verifyThat(SchedulingConstraintProvider::preferHighPriorityScheduled)
                .given(must)
                .penalizesBy(0);
    }

    @Test
    void preferHighConfidenceSlot_penalizesLowMoreThanHigh() {
        SchedulingTask lowConfidence = scheduledTask(1, BASE, 30, AvailabilityConfidence.LOW, ExecutionPriority.SHOULD, null);
        SchedulingTask highConfidence = scheduledTask(2, BASE, 30, AvailabilityConfidence.HIGH, ExecutionPriority.SHOULD, null);

        verifier.verifyThat(SchedulingConstraintProvider::preferHighConfidenceSlot)
                .given(lowConfidence)
                .penalizesBy(3);
        verifier.verifyThat(SchedulingConstraintProvider::preferHighConfidenceSlot)
                .given(highConfidence)
                .penalizesBy(0);
    }

    @Test
    void limitDailyLoad_penalizesExcessTasksOnSameDay() {
        SchedulingTask t1 = scheduledTask(1, BASE, 30);
        SchedulingTask t2 = scheduledTask(2, BASE.plusHours(1), 30);
        SchedulingTask t3 = scheduledTask(3, BASE.plusHours(2), 30);
        SchedulingTask t4 = scheduledTask(4, BASE.plusHours(3), 30);

        // 하루 소프트 상한(3)을 넘는 4번째 항목만큼 페널티가 붙는다.
        verifier.verifyThat(SchedulingConstraintProvider::limitDailyLoad)
                .given(t1, t2, t3, t4)
                .penalizesBy(2);
    }

    @Test
    void limitDailyLoad_doesNotPenalize_whenWithinLimit() {
        SchedulingTask t1 = scheduledTask(1, BASE, 30);
        SchedulingTask t2 = scheduledTask(2, BASE.plusHours(1), 30);

        verifier.verifyThat(SchedulingConstraintProvider::limitDailyLoad)
                .given(t1, t2)
                .penalizesBy(0);
    }
}
