package com.jungwoo.project.memo.scheduling.solver;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.domain.SchedulingContext;
import com.jungwoo.project.memo.scheduling.domain.SchedulingTask;

import java.time.Duration;

/**
 * 7일 범위 일정 후보 배치의 강한 규칙과 선호 규칙.
 *
 * 값 범위(SchedulingTask.candidateStarts) 생성 단계에서 이미 "가용시간 안", "현재 시각 이후",
 * "계획 범위 안", earliestStart/deadline 조건을 만족하는 시각만 후보로 넣기 때문에, 여기서는
 * 그 조건을 다시 한번 방어적으로 검사하는 것과 후보끼리의 관계(겹침, 하루 과부하, 우선순위)만
 * 다룬다. 규칙 수를 첫 구현에서 과도하게 늘리지 않는다.
 */
public class SchedulingConstraintProvider implements ConstraintProvider {

    /** 하루에 이 개수를 넘는 새 후보가 몰리면 소프트 페널티를 준다. */
    private static final int DAILY_LOAD_SOFT_LIMIT = 3;

    /** 같은 날 두 후보 사이 이 여유(분) 미만이면 소프트 페널티를 준다. */
    private static final int PREFERRED_BUFFER_MINUTES = 10;

    /**
     * 순서가 뒤집힌 쌍 하나당 페널티.
     *
     * 여유시간 부족(쌍당 1)보다 크고, 낮은 신뢰도 슬롯(3)과 같은 무게다 — 순서가 뒤집히는
     * 것이 여유가 없는 것보다는 큰 문제이지만, 배치를 포기할 만한 일은 아니다.
     *
     * 우선순위(preferHighPriorityScheduled)보다 낮아야 한다는 요구는 레벨이 보장한다.
     * 그쪽은 MEDIUM이고 이건 SOFT다.
     */
    private static final int ORDER_INVERSION_PENALTY = 3;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                taskOverlapsAnotherTask(factory),
                taskOverlapsBusyWindow(factory),
                taskOutsideHorizon(factory),
                taskStartsInPast(factory),
                taskPastDeadline(factory),
                preferHighPriorityScheduled(factory),
                preferHighConfidenceSlot(factory),
                limitDailyLoad(factory),
                preferOrderIndexSequence(factory),
                preferBufferBetweenTasks(factory),
        };
    }

    // ===== 강한 규칙 =====

    Constraint taskOverlapsAnotherTask(ConstraintFactory factory) {
        return factory.forEachUniquePair(SchedulingTask.class,
                        Joiners.filtering((a, b) -> a.isScheduled() && b.isScheduled()
                                && overlaps(a.getAssignedStart(), a.getAssignedEnd(),
                                b.getAssignedStart(), b.getAssignedEnd())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("New candidates must not overlap each other");
    }

    /** 기존 고정 일정·사용 불가 시간과 겹치지 않는다. */
    Constraint taskOverlapsBusyWindow(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(SchedulingTask::isScheduled)
                .join(BusyWindow.class,
                        Joiners.filtering((task, busy) -> overlaps(
                                task.getAssignedStart(), task.getAssignedEnd(),
                                busy.startAt(), busy.endAt())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Task must not overlap a busy window");
    }

    /** 계획 범위 밖에 배치하지 않는다. */
    Constraint taskOutsideHorizon(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(SchedulingTask::isScheduled)
                .join(SchedulingContext.class,
                        Joiners.filtering((task, ctx) ->
                                task.getAssignedStart().isBefore(ctx.horizonStart())
                                        || task.getAssignedEnd().isAfter(ctx.horizonEnd())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Task must stay within the planning horizon");
    }

    /** 현재 시각보다 과거에 배치하지 않는다. */
    Constraint taskStartsInPast(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(SchedulingTask::isScheduled)
                .join(SchedulingContext.class,
                        Joiners.filtering((task, ctx) -> task.getAssignedStart().isBefore(ctx.now())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Task must not start before now");
    }

    /** 명시된 마감일을 넘기지 않는다. */
    Constraint taskPastDeadline(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(task -> task.isScheduled() && task.getDeadline() != null)
                .filter(task -> task.getAssignedEnd().isAfter(task.getDeadline()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Task must not end after its deadline");
    }

    // ===== 선호 규칙 =====

    /**
     * MUST > SHOULD > OPTIONAL 순으로 배치를 우선한다.
     *
     * MEDIUM 레벨을 쓴다 — "배치되는가"는 "얼마나 좋은 시간에 배치되는가"(신뢰도, 하루 부하,
     * 여유시간 — 전부 SOFT)보다 항상 우선해야 한다. 같은 레벨(SOFT)에 두면 예를 들어 LOW
     * 신뢰도 페널티와 미배치 페널티 가중치가 우연히 같아질 때 "배치 안 함"과 "낮은 품질로
     * 배치함"이 동점 처리되어 솔버가 시간이 남는데도 임의로 미배치를 선택할 수 있다.
     *
     * SchedulingTask.assignedSlot은 allowsUnassigned=true라서 forEach()는 기본적으로 이
     * 항목들을 건너뛴다(대부분의 제약은 미배치를 신경 쓸 필요가 없기 때문) — 미배치 자체를
     * 다루는 이 제약만 forEachIncludingUnassigned를 쓴다.
     */
    Constraint preferHighPriorityScheduled(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(SchedulingTask.class)
                .filter(task -> !task.isScheduled())
                .penalize(HardMediumSoftScore.ONE_MEDIUM, task -> priorityWeight(task.getPriority()))
                .asConstraint("Prefer scheduling higher priority tasks first");
    }

    /** 신뢰도가 높은 가용시간을 먼저 사용한다. */
    Constraint preferHighConfidenceSlot(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(SchedulingTask::isScheduled)
                .penalize(HardMediumSoftScore.ONE_SOFT, task -> switch (task.getAssignedSlot().confidence()) {
                    case HIGH -> 0;
                    case MEDIUM -> 1;
                    case LOW -> 3;
                })
                .asConstraint("Prefer higher confidence availability slots");
    }

    /** 같은 날에 모든 일을 과도하게 몰지 않는다. */
    Constraint limitDailyLoad(ConstraintFactory factory) {
        return factory.forEach(SchedulingTask.class)
                .filter(SchedulingTask::isScheduled)
                .groupBy(task -> task.getAssignedStart().toLocalDate(), ConstraintCollectors.count())
                .filter((date, count) -> count > DAILY_LOAD_SOFT_LIMIT)
                .penalize(HardMediumSoftScore.ONE_SOFT, (date, count) -> (count - DAILY_LOAD_SOFT_LIMIT) * 2)
                .asConstraint("Avoid overloading a single day");
    }

    /**
     * 같은 프로젝트 안에서는 계획이 나열한 순서를 지킨다.
     *
     * order_index는 AI가 낸 배열 순서를 그대로 담고 있고 배치 조회도 그 순으로 정렬하지만,
     * 그건 솔버에 넣는 리스트의 순서일 뿐이라 솔버가 존중할 의무가 없었다. 파이썬 기초보다
     * 판다스가 먼저 배치돼도 제약 위반이 아니었다.
     *
     * 하드가 아니라 소프트인 이유: 앞선 항목을 놓을 슬롯이 없는데 뒤 항목은 놓을 수 있는
     * 상황에서 하드로 걸면 둘 다 미배치가 된다. 순서는 "지키면 좋은 것"이지 "어기면 안 되는
     * 것"이 아니다 — 사용자가 뒤 항목을 먼저 보고 싶어 할 수도 있고, order_index는 그걸
     * 알 수 없다.
     *
     * 같은 courseId 안에서만 비교한다. 과목이 다르면 선행 관계가 없고, 전체 order_index를
     * 하나의 순서로 취급하면 배치가 불필요하게 굳는다.
     *
     * 페널티를 시간 차이에 비례시키지 않는다. 30분 뒤집힌 것과 3일 뒤집힌 것은 문제의 성격이
     * 같은데, 분 단위로 재면 뒤집힌 쌍 하나가 다른 소프트 제약을 전부 압도한다. 뒤집힌 쌍이
     * 몇 개인지가 의미 있는 척도다.
     */
    Constraint preferOrderIndexSequence(ConstraintFactory factory) {
        return factory.forEachUniquePair(SchedulingTask.class,
                        Joiners.equal(SchedulingTask::getCourseId))
                .filter((a, b) -> a.getCourseId() != null
                        && a.getOrderIndex() != null && b.getOrderIndex() != null
                        && isOrderInverted(a, b))
                .penalize(HardMediumSoftScore.ONE_SOFT, (a, b) -> ORDER_INVERSION_PENALTY)
                .asConstraint("Prefer keeping the planned order within a project");
    }

    /** 가능하면 적절한 여유시간을 남긴다. */
    Constraint preferBufferBetweenTasks(ConstraintFactory factory) {
        return factory.forEachUniquePair(SchedulingTask.class,
                        Joiners.filtering((a, b) -> a.isScheduled() && b.isScheduled()
                                && a.getAssignedStart().toLocalDate().equals(b.getAssignedStart().toLocalDate())
                                && !overlaps(a.getAssignedStart(), a.getAssignedEnd(), b.getAssignedStart(), b.getAssignedEnd())
                                && gapMinutes(a, b) < PREFERRED_BUFFER_MINUTES))
                .penalize(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("Prefer leaving a buffer between tasks on the same day");
    }

    // ===== 헬퍼 =====

    private static boolean overlaps(java.time.LocalDateTime aStart, java.time.LocalDateTime aEnd,
                                     java.time.LocalDateTime bStart, java.time.LocalDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private static long gapMinutes(SchedulingTask a, SchedulingTask b) {
        long gapAfterA = Duration.between(a.getAssignedEnd(), b.getAssignedStart()).toMinutes();
        long gapAfterB = Duration.between(b.getAssignedEnd(), a.getAssignedStart()).toMinutes();
        return Math.max(gapAfterA, gapAfterB);
    }

    /**
     * 앞선 항목이 뒤 항목보다 늦게 시작하면 뒤집힘이다.
     *
     * forEachUniquePair는 어느 쪽을 a로 줄지 보장하지 않으므로 "a.orderIndex < b.orderIndex"만
     * 검사하면 쌍의 절반을 놓친다. 두 방향(순서·시각)이 어긋나는지를 본다.
     *
     * compareTo의 반환값을 그대로 비교하지 않는다 — LocalDateTime.compareTo는 -1/0/1이 아니라
     * 필드 차이를 돌려주므로 부호가 같아도 값이 달라 오판한다.
     */
    private static boolean isOrderInverted(SchedulingTask a, SchedulingTask b) {
        if (a.getOrderIndex().equals(b.getOrderIndex())) {
            return false;
        }
        if (a.getAssignedStart().isEqual(b.getAssignedStart())) {
            return false;
        }
        boolean aFirstByOrder = a.getOrderIndex() < b.getOrderIndex();
        boolean aFirstByTime = a.getAssignedStart().isBefore(b.getAssignedStart());
        return aFirstByOrder != aFirstByTime;
    }

    private static int priorityWeight(ExecutionPriority priority) {
        if (priority == null) {
            return 1;
        }
        return switch (priority) {
            case MUST -> 5;
            case SHOULD -> 3;
            case OPTIONAL -> 1;
        };
    }
}
