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
        attachAll(routines, routineMapper.findWeekdaysByUserId(userId));
        return routines;
    }

    /**
     * {@link #findAllWithWeekdays}의 잠금 판. 본체와 요일을 둘 다 잠금 조회로 읽어 같은 기준
     * (최신 커밋)을 유지한다. 계획 확정처럼 "지금 시간표"와 겹침을 검사하는 쓰기 트랜잭션에서만
     * 쓴다 — 목록·전개 화면이 이걸 쓰면 상담과 목록이 서로를 기다린다.
     */
    @Transactional
    public List<Routine> findAllWithWeekdaysForUpdate(Long userId) {
        List<Routine> routines = routineMapper.findAllByUserIdForUpdate(userId);
        if (routines.isEmpty()) {
            return routines;
        }
        attachAll(routines, routineMapper.findWeekdaysByUserIdForUpdate(userId));
        return routines;
    }

    private static void attachAll(List<Routine> routines, List<RoutineWeekdayRow> rows) {
        Map<Long, Set<DayOfWeek>> byRoutine = new HashMap<>();
        for (RoutineWeekdayRow row : rows) {
            byRoutine.computeIfAbsent(row.getRoutineId(), key -> new LinkedHashSet<>())
                    .add(DayOfWeek.valueOf(row.getDayOfWeek()));
        }
        for (Routine routine : routines) {
            routine.setDaysOfWeek(byRoutine.getOrDefault(routine.getRoutineId(), new LinkedHashSet<>()));
        }
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
