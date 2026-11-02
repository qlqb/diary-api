package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 아직 적용하지 않은 반복 일정 변경을 전개 결과에 겹쳐 보는 값(가정 상태). DB는 건드리지 않는다.
 *
 * <p>전개 규칙(자정 넘김·이동 회차·반열린 창)을 다시 쓰지 않으려고 {@link RoutineOccurrenceService}의 같은 전개에 끼워 넣는다.
 * 회차는 언제나 (routineId, 원래 날짜)로 가리킨다 — 이동한 날짜가 아니다.
 */
public final class RoutineOverlay {

    public static final RoutineOverlay NONE = new RoutineOverlay(Map.of(), Map.of());

    /** routineId → 바뀐 규칙(새로 만드는 규칙은 음수 id). */
    private final Map<Long, Routine> routines;
    /** (routineId, 원래 날짜) → 그날의 예외. 값이 null이면 그날 예외를 지운 것(복원)이다. */
    private final Map<OccurrenceKey, RoutineException> exceptions;

    private RoutineOverlay(Map<Long, Routine> routines, Map<OccurrenceKey, RoutineException> exceptions) {
        this.routines = routines;
        this.exceptions = exceptions;
    }

    public record OccurrenceKey(Long routineId, LocalDate sourceDate) {
    }

    public boolean isEmpty() {
        return routines.isEmpty() && exceptions.isEmpty();
    }

    Map<Long, Routine> routines() {
        return routines;
    }

    Map<OccurrenceKey, RoutineException> exceptions() {
        return exceptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Long, Routine> routines = new LinkedHashMap<>();
        private final Map<OccurrenceKey, RoutineException> exceptions = new HashMap<>();

        public Builder putRoutine(Routine routine) {
            routines.put(routine.getRoutineId(), routine);
            return this;
        }

        /** 그날 예외를 이 값으로 둔다(SKIP·MOVED). */
        public Builder putException(RoutineException exception) {
            exceptions.put(new OccurrenceKey(exception.getRoutineId(), exception.getExceptionDate()), exception);
            return this;
        }

        /** 그날 예외를 지운다 — 원래 회차가 돌아온다. */
        public Builder removeException(Long routineId, LocalDate sourceDate) {
            exceptions.put(new OccurrenceKey(routineId, sourceDate), null);
            return this;
        }

        public RoutineOverlay build() {
            if (routines.isEmpty() && exceptions.isEmpty()) {
                return NONE;
            }
            return new RoutineOverlay(Collections.unmodifiableMap(new LinkedHashMap<>(routines)),
                    Collections.unmodifiableMap(new HashMap<>(exceptions)));
        }
    }
}
