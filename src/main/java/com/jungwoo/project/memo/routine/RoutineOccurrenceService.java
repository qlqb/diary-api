package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 반복 규칙에서 특정 기간의 발생분을 계산한다. 행을 만들지 않는다.
 *
 * <p><b>전개는 하나다.</b> 배치용/표시용 메서드를 나누지 않는다. 나뉘어 있으면 한쪽에만
 * 걸리는 규칙이 생길 수 있고, 그 순간 "화면에는 보이는데 배치는 그 시간에 학습을 넣는"
 * 상태가 가능해진다 — 표시와 배치가 어긋나는 것이 이 기능의 최악의 실패 모드다. 같은
 * expand를 보면 그 어긋남이 구조적으로 불가능하다.
 */
@Service
@RequiredArgsConstructor
public class RoutineOccurrenceService {

    private final RoutineReader routineReader;
    private final RoutineExceptionMapper routineExceptionMapper;

    /**
     * @param from 창의 첫 날(포함)
     * @param to   창의 마지막 날(포함)
     */
    @Transactional(readOnly = true)
    public List<RoutineOccurrence> expand(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return List.of();
        }

        /*
         * 창 하루 앞에서부터 훑는다. 전날 22:00에 시작해 창 안으로 이어지는 발생분이 있기
         * 때문이다(자정 넘김). 이동 목적지(moved_date)도 같은 이유로 하루 앞부터 본다.
         */
        LocalDate scanFrom = from.minusDays(1);
        LocalDateTime windowStart = from.atStartOfDay();
        LocalDateTime windowEnd = to.plusDays(1).atStartOfDay();

        List<Routine> routines = routineReader.findAllWithWeekdays(userId);
        if (routines.isEmpty()) {
            return List.of();
        }
        Map<Long, Routine> routineById = new HashMap<>();
        for (Routine routine : routines) {
            routineById.put(routine.getRoutineId(), routine);
        }

        // (routineId, exceptionDate) -> 예외. 원본 발생일을 건너뛰는 데만 쓴다.
        Map<Long, Map<LocalDate, RoutineException>> exceptionsBySourceDate = new HashMap<>();
        for (RoutineException exception :
                routineExceptionMapper.findByUserIdAndExceptionDateRange(userId, scanFrom, to)) {
            exceptionsBySourceDate
                    .computeIfAbsent(exception.getRoutineId(), key -> new HashMap<>())
                    .put(exception.getExceptionDate(), exception);
        }

        List<RoutineOccurrence> occurrences = new ArrayList<>();

        // 1. 규칙이 만드는 원본 발생분.
        for (Routine routine : routines) {
            Map<LocalDate, RoutineException> exceptions =
                    exceptionsBySourceDate.getOrDefault(routine.getRoutineId(), Map.of());
            for (LocalDate date = scanFrom; !date.isAfter(to); date = date.plusDays(1)) {
                if (date.isBefore(routine.getEffectiveFrom())) {
                    continue;
                }
                if (routine.getEffectiveUntil() != null && date.isAfter(routine.getEffectiveUntil())) {
                    continue;
                }
                if (!routine.getDaysOfWeek().contains(date.getDayOfWeek())) {
                    continue;
                }
                // SKIP은 그날이 없는 것이고, MOVED는 2번에서 목적지로 다시 나온다.
                if (exceptions.containsKey(date)) {
                    continue;
                }
                occurrences.add(occurrence(routine, date, routine.getStartTime(), routine.getEndTime(),
                        routine.getLocation(), date, false));
            }
        }

        /*
         * 2. 이동해 온 발생분. 이게 핵심이다 — 이동한 날이 창 안이고 원래 날은 창 밖일 수
         * 있다. 9/24 -> 10/1 이동에서 창이 9/28~10/4면 원래 날은 밖, 목적지는 안이다.
         * 1번만 돌리면 이 수업이 통째로 사라지고, 앱은 10/1을 비어 있다고 보고 학습을 배치한다.
         *
         * movedDate에는 effectiveFrom/Until을 적용하지 않는다. 마지막 수업 보강이 종강
         * 다음 날로 밀리는 것은 정상이고, 학기 밖으로 옮겼다고 무효 처리하면 안 된다.
         */
        for (RoutineException exception :
                routineExceptionMapper.findByUserIdAndMovedDateRange(userId, scanFrom, to)) {
            if (exception.getType() != RoutineExceptionType.MOVED || exception.getMovedDate() == null) {
                continue;
            }
            Routine routine = routineById.get(exception.getRoutineId());
            if (routine == null) {
                continue;
            }
            LocalTime startTime = exception.getMovedStartTime() != null
                    ? exception.getMovedStartTime() : routine.getStartTime();
            LocalTime endTime = exception.getMovedEndTime() != null
                    ? exception.getMovedEndTime() : routine.getEndTime();
            String location = exception.getMovedLocation() != null
                    ? exception.getMovedLocation() : routine.getLocation();
            occurrences.add(occurrence(routine, exception.getMovedDate(), startTime, endTime, location,
                    exception.getExceptionDate(), true));
        }

        // 3. 반열린 구간으로 거른다. 경계 처리를 구현자 판단에 맡기지 않는다 — 자정 넘김
        //    때문에 발생분이 창 밖에서 시작하거나 창 밖에서 끝날 수 있다.
        List<RoutineOccurrence> inWindow = new ArrayList<>();
        for (RoutineOccurrence occurrence : occurrences) {
            if (occurrence.startAt().isBefore(windowEnd) && occurrence.endAt().isAfter(windowStart)) {
                inWindow.add(occurrence);
            }
        }
        inWindow.sort(Comparator.comparing(RoutineOccurrence::startAt)
                .thenComparing(RoutineOccurrence::routineId));
        return inWindow;
    }

    /**
     * endTime이 startTime보다 이르거나 같으면 다음 날이다. 근무표의 CL = 15~00이 실제 사례다.
     *
     * <p>창 밖으로 삐져나가는 부분을 잘라내지 않는다. 잘라내면 일요일 새벽에 배치가 된다.
     */
    private RoutineOccurrence occurrence(Routine routine, LocalDate date, LocalTime startTime,
                                          LocalTime endTime, String location, LocalDate sourceDate,
                                          boolean moved) {
        LocalDateTime startAt = LocalDateTime.of(date, startTime);
        LocalDateTime endAt = endTime.isAfter(startTime)
                ? LocalDateTime.of(date, endTime)
                : LocalDateTime.of(date.plusDays(1), endTime);
        return new RoutineOccurrence(routine.getRoutineId(), routine.getCourseId(), routine.getTitle(),
                location, startAt, endAt, sourceDate, moved);
    }
}
