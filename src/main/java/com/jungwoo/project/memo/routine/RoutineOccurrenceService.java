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
 *
 * <p><b>이동시간(lead) 발생분도 여기서 나온다.</b> 루틴에 leadMinutes &gt; 0이 있으면 원
 * 발생분 앞 구간을 {@code lead=true}인 발생분으로 하나 더 낸다. 별도 메서드로 두지 않는
 * 이유는 위와 같다 — 가용시간이 보는 것과 화면이 보는 것이 같아야 한다. "같은 날 수업은
 * 첫 수업 앞에만"이라는 통학 정책도 여기 한 곳에만 있다.
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

        /*
         * 3. 이동시간 발생분. 1·2번이 끝난 뒤의 발생분에서만 만든다 — is_deleted는 SQL이,
         *    effective_from/until·SKIP은 1번이 이미 걸렀다. 필터 전 루틴에서 만들면 지워진
         *    루틴의 이동시간이 가용시간을 막는다.
         */
        occurrences.addAll(leadOccurrences(occurrences, routineById));

        // 4. 반열린 구간으로 거른다. 경계 처리를 구현자 판단에 맡기지 않는다 — 자정 넘김
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
     * 이동시간이 있는 발생분 앞에 lead 발생분을 낸다.
     *
     * <p><b>수업 통학 정책: 같은 날 수업은 첫 수업 앞에만.</b> 수요일 09:00~12:00 수업 뒤 13:00
     * 수업이면 08:00~09:00 하나다 — 첫 수업에 가면 이미 그 자리에 있다. "수업"은
     * courseId != null인 루틴이고, "첫"은 그날(startAt의 날짜) 안에서 이동시간이 있는 수업
     * 발생분 중 가장 이른 것이다(시각이 같으면 routineId가 작은 쪽, 결정적).
     *
     * <p>이 규칙은 수업에만 건다. 수업이 아닌 루틴(알바·운동)의 이동시간은 발생분마다 붙는다 —
     * "첫 것만"은 통학의 사실이지 이동시간 자체의 규칙이 아니라서, 여기서 일반화하지 않는다.
     *
     * <p>약속은 보지 않는다. 약속이 lead 구간과 겹치면 가용시간 계산이 hardBusy를 합집합으로
     * 깎으므로 시간이 두 번 빠지지 않는다 — 여기서 억제할 이유가 없고, 억제하면 약속 하나가
     * 이동시간을 조용히 지우는 경로가 된다.
     *
     * <p>leadStart가 자정 앞으로 넘어가면 그날 00:00으로 자른다. 잘라서 길이가 0이면(00:00
     * 시작) 만들지 않는다.
     */
    private List<RoutineOccurrence> leadOccurrences(List<RoutineOccurrence> base, Map<Long, Routine> routineById) {
        List<RoutineOccurrence> targets = new ArrayList<>();
        Map<LocalDate, RoutineOccurrence> firstClassByDate = new HashMap<>();
        for (RoutineOccurrence occurrence : base) {
            Routine routine = routineById.get(occurrence.routineId());
            if (routine == null || routine.getLeadMinutes() == null || routine.getLeadMinutes() <= 0) {
                continue;
            }
            if (occurrence.courseId() == null) {
                targets.add(occurrence);
                continue;
            }
            firstClassByDate.merge(occurrence.startAt().toLocalDate(), occurrence, (a, b) -> {
                int byStart = a.startAt().compareTo(b.startAt());
                if (byStart != 0) {
                    return byStart < 0 ? a : b;
                }
                return a.routineId() <= b.routineId() ? a : b;
            });
        }
        targets.addAll(firstClassByDate.values());

        List<RoutineOccurrence> leads = new ArrayList<>();
        for (RoutineOccurrence occurrence : targets) {
            Routine routine = routineById.get(occurrence.routineId());
            LocalDateTime leadEnd = occurrence.startAt();
            LocalDateTime dayStart = leadEnd.toLocalDate().atStartOfDay();
            LocalDateTime leadStart = leadEnd.minusMinutes(routine.getLeadMinutes());
            if (leadStart.isBefore(dayStart)) {
                leadStart = dayStart;
            }
            if (leadStart.isBefore(leadEnd)) {
                leads.add(occurrence.leadFrom(leadStart));
            }
        }
        return leads;
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
