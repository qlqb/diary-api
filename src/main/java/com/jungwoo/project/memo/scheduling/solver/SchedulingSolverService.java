package com.jungwoo.project.memo.scheduling.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.jungwoo.project.memo.scheduling.domain.SchedulingPlan;
import com.jungwoo.project.memo.scheduling.domain.SchedulingTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Timefold Solver를 짧게 한 번 돌리는 용도로만 쓴다. 상시 SolverManager나 데몬을 두지 않고,
 * 사용자가 "다시 배치"를 누를 때마다 요청 단위로 SolverFactory/Solver를 새로 만들고 버린다 —
 * Solver 인스턴스는 스레드 안전하지 않고 재사용을 전제하지 않으므로 매 호출 새로 만드는 편이
 * 오히려 안전하다. 실행 시간은 scheduling.solver.timeout-seconds로 짧게 제한한다.
 */
@Slf4j
@Service
public class SchedulingSolverService {

    private final int timeoutSeconds;

    public SchedulingSolverService(@Value("${scheduling.solver.timeout-seconds:5}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public SchedulingPlan solve(SchedulingPlan problem) {
        if (problem.getTasks() == null || problem.getTasks().isEmpty()) {
            return problem;
        }

        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(SchedulingPlan.class)
                .withEntityClasses(SchedulingTask.class)
                .withConstraintProviderClass(SchedulingConstraintProvider.class)
                .withTerminationConfig(new TerminationConfig().withSecondsSpentLimit((long) timeoutSeconds));

        SolverFactory<SchedulingPlan> solverFactory = SolverFactory.create(solverConfig);
        Solver<SchedulingPlan> solver = solverFactory.buildSolver();

        long startedAt = System.currentTimeMillis();
        SchedulingPlan solution = solver.solve(problem);
        log.info("일정 후보 배치 계산 완료: taskCount={}, tookMs={}, score={}",
                problem.getTasks().size(), System.currentTimeMillis() - startedAt, solution.getScore());
        return solution;
    }
}
