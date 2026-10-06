package com.jungwoo.project.memo.scheduling.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;

import java.util.List;

/**
 * 한 번의 재배치 계산 단위. 새 PROPOSAL 항목(최대 5개)과 기존 바쁜 시간을 담아 Timefold에
 * 넘기고, 풀이 결과는 각 SchedulingTask.assignedSlot으로 돌아온다.
 *
 * 이 클래스는 요청마다 새로 만들어 SchedulingSolverService.solve()에 한 번 넘기고 버린다 —
 * 상시 보관되는 Solver 상태가 아니다.
 */
@PlanningSolution
public class SchedulingPlan {

    @PlanningEntityCollectionProperty
    private List<SchedulingTask> tasks;

    @ProblemFactCollectionProperty
    private List<BusyWindow> busyWindows;

    @ProblemFactProperty
    private SchedulingContext context;

    @PlanningScore
    private HardMediumSoftScore score;

    public SchedulingPlan() {
    }

    public SchedulingPlan(List<SchedulingTask> tasks, List<BusyWindow> busyWindows, SchedulingContext context) {
        this.tasks = tasks;
        this.busyWindows = busyWindows;
        this.context = context;
    }

    public List<SchedulingTask> getTasks() {
        return tasks;
    }

    public List<BusyWindow> getBusyWindows() {
        return busyWindows;
    }

    public SchedulingContext getContext() {
        return context;
    }

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }
}
