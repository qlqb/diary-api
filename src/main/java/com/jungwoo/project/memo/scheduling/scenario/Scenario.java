package com.jungwoo.project.memo.scheduling.scenario;

import com.jungwoo.project.memo.assignment.AssignmentOverlay;
import com.jungwoo.project.memo.commitment.CommitmentOverlay;
import com.jungwoo.project.memo.execution.ExecutionOverlay;
import com.jungwoo.project.memo.routine.RoutineOverlay;

import java.util.List;

/**
 * 계산용 상태(가정 상태) 하나 = 원본 + 아직 적용하지 않은 변경. 불변이다.
 *
 * <p>가용시간·예산·모델 입력·재생성·미리보기·확정 검증이 모두 이 값 하나로 같은 상태를 본다. 원본만 보는 계산은 {@link #ORIGINAL}이다.
 * 변경은 도메인 op가 {@link ScenarioProjection}으로 자기 겹침에만 쓴다 — 이 클래스는 어떤 op가 있는지 모른다.
 *
 * @param ref 이 상태를 만든 변경 묶음(검토 리비전·선택 항목). 원본이면 null
 */
public record Scenario(RoutineOverlay routines, CommitmentOverlay commitments, ExecutionOverlay execution,
                       AssignmentOverlay assignments, ScenarioRef ref, StudyWindows study) {

    public static final Scenario ORIGINAL = new Scenario(RoutineOverlay.NONE, CommitmentOverlay.NONE,
            ExecutionOverlay.NONE, AssignmentOverlay.NONE, null, null);

    public Scenario(RoutineOverlay routines, CommitmentOverlay commitments, ExecutionOverlay execution,
                    AssignmentOverlay assignments, ScenarioRef ref) {
        this(routines, commitments, execution, assignments, ref, null);
    }

    /**
     * 사용자가 말한 공부 범위(그날 기본 창을 대신한다)와 그 안에서 뺄 구간(식사 등). 날짜가 붙은 실제 시각이라 자정을 넘을 수 있다.
     * 범위가 있는 날만 기본 창을 대신하고, 없는 날은 기본 창 그대로다. 실제 일정(hard busy)은 언제나 뺀다.
     */
    public record StudyWindows(List<TimeRange> windows, List<TimeRange> exclusions) {
        public StudyWindows {
            windows = windows == null ? List.of() : List.copyOf(windows);
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        }

        public boolean isEmpty() {
            return windows.isEmpty() && exclusions.isEmpty();
        }
    }

    public record TimeRange(java.time.LocalDateTime start, java.time.LocalDateTime end) {
    }

    /** 같은 겹침에 공부 범위만 바꾼다. */
    public Scenario withStudy(StudyWindows study) {
        return new Scenario(routines, commitments, execution, assignments, ref, study);
    }

    /**
     * @param bundleId    변경 묶음
     * @param revision    검토한 묶음 리비전
     * @param itemIds     반영한(선택한) 항목
     * @param fingerprint 정규화한 op 내용의 해시 — 같은 리비전이라도 내용이 같음을 확인한다
     */
    public record ScenarioRef(Long bundleId, long revision, List<Long> itemIds, String fingerprint) {
        public ScenarioRef {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    public boolean isOriginal() {
        return routines.isEmpty() && commitments.isEmpty() && execution.isEmpty() && assignments.isEmpty();
    }

    public static Scenario of(List<? extends ScenarioProjection> changes, ScenarioRef ref) {
        Builder builder = new Builder();
        for (ScenarioProjection change : changes) {
            change.project(builder);
        }
        return builder.build(ref);
    }

    /** 도메인 op가 자기 겹침을 쓰는 곳. */
    public static final class Builder {
        private final RoutineOverlay.Builder routines = RoutineOverlay.builder();
        private final CommitmentOverlay.Builder commitments = CommitmentOverlay.builder();
        private final ExecutionOverlay.Builder execution = ExecutionOverlay.builder();
        private final AssignmentOverlay.Builder assignments = AssignmentOverlay.builder();

        public RoutineOverlay.Builder routines() {
            return routines;
        }

        public CommitmentOverlay.Builder commitments() {
            return commitments;
        }

        public ExecutionOverlay.Builder execution() {
            return execution;
        }

        public AssignmentOverlay.Builder assignments() {
            return assignments;
        }

        private long nextTemp = -1_000_000_000L;

        /** 새로 만드는 것의 임시 id. 묶음 안에서 겹치지 않는다(실제 id와도 겹치지 않는 음수). */
        public long nextTempId() {
            return nextTemp--;
        }

        Scenario build(ScenarioRef ref) {
            return new Scenario(routines.build(), commitments.build(), execution.build(), assignments.build(), ref);
        }
    }
}
