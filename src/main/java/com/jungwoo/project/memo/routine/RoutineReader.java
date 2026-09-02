package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 루틴을 요일까지 붙여서 읽는 한 곳.
 *
 * <p>목록 화면(RoutineService)과 전개(RoutineOccurrenceService)가 둘 다 "루틴 + 그 요일"을
 * 필요로 한다. 조립을 양쪽에 복사하면 한쪽만 고쳐질 수 있어 여기 하나로 모은다.
 *
 * <p>루틴이 몇 개든 조회는 두 번이다 — 루틴 목록 한 번, 그 사용자의 요일 전부 한 번.
 */
@Component
@RequiredArgsConstructor
public class RoutineReader {

    private final RoutineMapper routineMapper;

    @Transactional(readOnly = true)
    public List<Routine> findAllWithWeekdays(Long userId) {
        List<Routine> routines = routineMapper.findAllByUserId(userId);
        if (routines.isEmpty()) {
            return routines;
        }
        Map<Long, Set<DayOfWeek>> byRoutine = new HashMap<>();
        for (RoutineWeekdayRow row : routineMapper.findWeekdaysByUserId(userId)) {
            byRoutine.computeIfAbsent(row.getRoutineId(), key -> new LinkedHashSet<>())
                    .add(DayOfWeek.valueOf(row.getDayOfWeek()));
        }
        for (Routine routine : routines) {
            routine.setDaysOfWeek(byRoutine.getOrDefault(routine.getRoutineId(), new LinkedHashSet<>()));
        }
        return routines;
    }

    /** 한 건. 없거나 남의 것이면 null이다 — 404/403 판단은 부르는 쪽이 한다. */
    @Transactional(readOnly = true)
    public Routine findOneWithWeekdays(Long userId, Long routineId) {
        Routine routine = routineMapper.findByIdAndUserId(routineId, userId);
        if (routine == null) {
            return null;
        }
        attachWeekdays(routine);
        return routine;
    }

    /** 이미 잠가서 읽은 행에 요일만 붙일 때. 잠금을 다시 잡지 않는다. */
    public void attachWeekdays(Routine routine) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String day : new ArrayList<>(routineMapper.findWeekdaysByRoutineId(routine.getRoutineId()))) {
            days.add(DayOfWeek.valueOf(day));
        }
        routine.setDaysOfWeek(days);
    }
}
