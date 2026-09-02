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

    /** 같은 프로젝트(courseId)에 속하고 order_index를 가진 배치된 태스크. */
    private SchedulingTask orderedTask(long id, Long courseId, Integer orderIndex, LocalDateTime start) {
        SchedulingTask task = new SchedulingTask(id, "task-" + id, 30, ExecutionPriority.SHOULD, null,
                courseId, orderIndex, List.of());
        task.setAssignedSlot(new TimeSlotOption(start, AvailabilityConfidence.HIGH));
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

    // ===== 같은 프로젝트 안의 배치 순서 =====

    @Test
    void preferOrderIndexSequence_doesNotPenalize_whenOrderAndTimeAgree() {
        SchedulingTask first = orderedTask(1, 10L, 0, BASE);
        SchedulingTask second = orderedTask(2, 10L, 1, BASE.plusHours(2));

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(first, second)
                .penalizesBy(0);
    }

    @Test
    void preferOrderIndexSequence_penalizesInvertedPair() {
        SchedulingTask first = orderedTask(1, 10L, 0, BASE.plusHours(2));
        SchedulingTask second = orderedTask(2, 10L, 1, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(first, second)
                .penalizesBy(3);
    }

    /**
     * forEachUniquePair는 어느 쪽을 a로 줄지 보장하지 않는다. 쌍을 뒤집어 넣어도 같은
     * 위반으로 잡혀야 한다 — "a.orderIndex < b.orderIndex"만 검사하면 절반을 놓친다.
     */
    @Test
    void preferOrderIndexSequence_catchesInversion_regardlessOfPairDirection() {
        SchedulingTask later = orderedTask(1, 10L, 1, BASE);
        SchedulingTask earlier = orderedTask(2, 10L, 0, BASE.plusHours(2));

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(later, earlier)
                .penalizesBy(3);
    }

    /** 과목이 다르면 선행 관계가 없다. 전체 order_index를 하나의 순서로 취급하지 않는다. */
    @Test
    void preferOrderIndexSequence_ignoresTasksFromDifferentProjects() {
        SchedulingTask a = orderedTask(1, 10L, 0, BASE.plusHours(2));
        SchedulingTask b = orderedTask(2, 20L, 1, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(a, b)
                .penalizesBy(0);
    }

    /** 프로젝트에 묶이지 않은 할 일에는 순서 개념이 없다. */
    @Test
    void preferOrderIndexSequence_ignoresTasksWithoutProject() {
        SchedulingTask a = orderedTask(1, null, 0, BASE.plusHours(2));
        SchedulingTask b = orderedTask(2, null, 1, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(a, b)
                .penalizesBy(0);
    }

    @Test
    void preferOrderIndexSequence_ignoresTiedOrderIndex() {
        SchedulingTask a = orderedTask(1, 10L, 2, BASE.plusHours(2));
        SchedulingTask b = orderedTask(2, 10L, 2, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(a, b)
                .penalizesBy(0);
    }

    @Test
    void preferOrderIndexSequence_ignoresTasksStartingAtTheSameTime() {
        SchedulingTask a = orderedTask(1, 10L, 1, BASE);
        SchedulingTask b = orderedTask(2, 10L, 0, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(a, b)
                .penalizesBy(0);
    }

    /** 미배치는 비교할 시각이 없다. forEach가 이미 건너뛴다. */
    @Test
    void preferOrderIndexSequence_ignoresUnscheduledTasks() {
        SchedulingTask scheduled = orderedTask(1, 10L, 1, BASE);
        SchedulingTask unscheduled = new SchedulingTask(2L, "task-2", 30, ExecutionPriority.SHOULD, null,
                10L, 0, List.of());

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(scheduled, unscheduled)
                .penalizesBy(0);
    }

    /**
     * 페널티는 위반 쌍 개수에 비례한다. 시간 차이에 비례시키면 이 값이 달라진다 —
     * 30분 뒤집힌 것과 3일 뒤집힌 것은 문제의 성격이 같다.
     */
    @Test
    void preferOrderIndexSequence_accumulatesPerInvertedPair() {
        // 순서 0·1·2를 정확히 거꾸로 배치하면 뒤집힌 쌍이 셋이다.
        SchedulingTask a = orderedTask(1, 10L, 0, BASE.plusHours(4));
        SchedulingTask b = orderedTask(2, 10L, 1, BASE.plusHours(2));
        SchedulingTask c = orderedTask(3, 10L, 2, BASE);

        verifier.verifyThat(SchedulingConstraintProvider::preferOrderIndexSequence)
                .given(a, b, c)
                .penalizesBy(9);
    }
}
