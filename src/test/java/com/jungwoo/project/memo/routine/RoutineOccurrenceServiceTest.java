package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 전개 규칙 단위 테스트.
 *
 * <p>날짜는 실제 강의계획서에서 가져왔다 — 목요일 수업(빅데이터분석 10:00~12:50)과 추석
 * 보강(9/24 목 -&gt; 10/1 목)이다. 임의의 날짜보다 이쪽이 낫다: 원래 날은 창 밖인데 목적지만
 * 창 안인 경우가 실제로 이 일정에서 나오고, 그게 전개에서 가장 틀리기 쉬운 자리다.
 *
 * <p>RoutineReader는 목이 아니라 실물을 쓴다. 요일을 별도 조회로 읽어 붙이는 조립이 전개
 * 결과를 좌우하므로, 그 조립까지 함께 지나가야 이 테스트가 의미가 있다.
 */
@ExtendWith(MockitoExtension.class)
class RoutineOccurrenceServiceTest {

    private static final Long USER_ID = 1L;

    // 2026-09-21(월) ~ 2026-09-27(일). 목요일은 9/24.
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 9, 27);

    // 2026-09-28(월) ~ 2026-10-04(일). 목요일은 10/1.
    private static final LocalDate NEXT_WEEK_FROM = LocalDate.of(2026, 9, 28);
    private static final LocalDate NEXT_WEEK_TO = LocalDate.of(2026, 10, 4);

    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 8, 25);
    private static final LocalDate SEMESTER_END = LocalDate.of(2026, 12, 11);

    @Mock
    private RoutineMapper routineMapper;

    @Mock
    private RoutineExceptionMapper routineExceptionMapper;

    private RoutineOccurrenceService service;

    private RoutineOccurrenceService service() {
        if (service == null) {
            service = new RoutineOccurrenceService(new RoutineReader(routineMapper), routineExceptionMapper);
        }
        return service;
    }

    // ===== 기본 =====

    @Test
    void 매주_목요일_루틴은_한_주_창에서_한_번_나온다() {
        given(List.of(thursdayClass()), List.of());

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, WEEK_FROM, WEEK_TO);

        assertThat(occurrences).hasSize(1);
        RoutineOccurrence occurrence = occurrences.get(0);
        assertThat(occurrence.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 10, 0));
        assertThat(occurrence.endAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 12, 50));
        assertThat(occurrence.sourceDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(occurrence.moved()).isFalse();
        assertThat(occurrence.location()).isEqualTo("3-315");
    }

    @Test
    void effectiveFrom_이전에는_나오지_않는다() {
        Routine routine = thursdayClass();
        routine.setEffectiveFrom(LocalDate.of(2026, 9, 25));
        given(List.of(routine), List.of());

        assertThat(service().expand(USER_ID, WEEK_FROM, WEEK_TO)).isEmpty();
    }

    @Test
    void effectiveUntil_이후에는_나오지_않는다() {
        Routine routine = thursdayClass();
        routine.setEffectiveUntil(LocalDate.of(2026, 9, 23));
        given(List.of(routine), List.of());

        assertThat(service().expand(USER_ID, WEEK_FROM, WEEK_TO)).isEmpty();
    }

    @Test
    void effectiveUntil이_없으면_창_끝까지_나온다() {
        Routine routine = thursdayClass();
        routine.setEffectiveUntil(null);
        given(List.of(routine), List.of());

        // 2027년의 어느 주를 봐도 여전히 돈다 — 무기한이라는 뜻이다. 2027-03-04가 목요일.
        List<RoutineOccurrence> occurrences =
                service().expand(USER_ID, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 7));

        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).startAt().toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 4));
    }

    /**
     * 소프트 삭제 필터는 SQL(is_deleted = 0)에 있다. 이 테스트가 확인하는 것은 그 조건이
     * 아니라, 매퍼가 돌려주지 않은 루틴을 전개가 다른 경로로 되살리지 않는다는 것이다 —
     * 이동 예외 조회가 별도 경로라 그 가능성이 실제로 있다.
     */
    @Test
    void 매퍼가_돌려주지_않은_루틴은_이동_예외로도_되살아나지_않는다() {
        given(List.of(), List.of(moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25))));

        assertThat(service().expand(USER_ID, WEEK_FROM, WEEK_TO)).isEmpty();
    }

    @Test
    void 요일이_여럿이면_창_안에서_각각_나온다() {
        Routine routine = routine(2L, "알바", null, LocalTime.of(18, 0), LocalTime.of(22, 0),
                SEMESTER_START, null, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        given(List.of(routine), List.of());

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, WEEK_FROM, WEEK_TO);

        assertThat(occurrences).extracting(o -> o.startAt().toLocalDate())
                .containsExactly(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 23));
    }

    // ===== 예외 =====

    @Test
    void SKIP_예외가_있는_날은_나오지_않는다() {
        given(List.of(thursdayClass()), List.of(skip(1L, LocalDate.of(2026, 9, 24))));

        assertThat(service().expand(USER_ID, WEEK_FROM, WEEK_TO)).isEmpty();
    }

    @Test
    void MOVED는_원래_날에서_사라지고_목적지에_나온다() {
        // 9/24(목) 수업을 같은 주 9/25(금)로 옮긴다.
        given(List.of(thursdayClass()),
                List.of(moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25))));

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, WEEK_FROM, WEEK_TO);

        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).startAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(occurrences.get(0).sourceDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(occurrences.get(0).moved()).isTrue();
    }

    /**
     * 추석 보강(9/24 -&gt; 10/1)을 10/1이 든 주에서 조회한다. 원래 날은 창 밖이다.
     * 이걸 놓치면 앱은 10/1을 비어 있다고 보고 그 자리에 학습을 배치한다.
     *
     * <p>10/1은 그 자체로 정규 목요일이기도 하다. 그래서 그날 발생분이 둘이 되는 것이 맞다 —
     * 보강은 정규 수업을 대체하는 것이 아니라 그 주에 얹히는 것이고, 둘 다 실제로 가야 한다.
     * 배치는 두 구간 모두 피해야 하므로 여기서 하나로 합치지 않는다.
     */
    @Test
    void 목적지만_창_안이어도_나온다() {
        given(List.of(thursdayClass()),
                List.of(moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 10, 1))));

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, NEXT_WEEK_FROM, NEXT_WEEK_TO);

        assertThat(occurrences).hasSize(2);
        assertThat(occurrences).allSatisfy(occurrence ->
                assertThat(occurrence.startAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 10, 0)));
        assertThat(occurrences).filteredOn(RoutineOccurrence::moved)
                .singleElement()
                .extracting(RoutineOccurrence::sourceDate)
                .isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(occurrences).filteredOn(occurrence -> !occurrence.moved())
                .singleElement()
                .extracting(RoutineOccurrence::sourceDate)
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void 원래_날만_창_안이면_나오지_않는다() {
        given(List.of(thursdayClass()),
                List.of(moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 10, 1))));

        assertThat(service().expand(USER_ID, WEEK_FROM, WEEK_TO)).isEmpty();
    }

    /** 종강 12/11 뒤의 12/18 보강. 학기 밖으로 옮겼다고 무효 처리하면 안 된다. */
    @Test
    void 목적지가_effectiveUntil_이후여도_나온다() {
        given(List.of(thursdayClass()),
                List.of(moved(1L, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 18))));

        List<RoutineOccurrence> occurrences =
                service().expand(USER_ID, LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20));

        assertThat(occurrences).extracting(o -> o.startAt().toLocalDate())
                .containsExactly(LocalDate.of(2026, 12, 18));
    }

    /**
     * 중간에 그만두며 effectiveUntil을 당겨도 그 밖에 남은 보강은 계속 나타난다. 결함이 아니라
     * 기본값이다 — 마지막 근무 이후의 잔여 보강·대타를 실제로 가는 경우가 있고, 안 갈
     * 것이라면 그 예외를 명시적으로 삭제한다. 조용히 지우는 것보다 사용자가 정하는 쪽이 맞다.
     */
    @Test
    void effectiveUntil을_당긴_뒤에도_기간_밖_보강은_남는다() {
        Routine routine = thursdayClass();
        routine.setEffectiveUntil(LocalDate.of(2026, 10, 1));
        given(List.of(routine),
                List.of(moved(1L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5))));

        List<RoutineOccurrence> occurrences =
                service().expand(USER_ID, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11));

        assertThat(occurrences).extracting(o -> o.startAt().toLocalDate())
                .containsExactly(LocalDate.of(2026, 10, 5));
    }

    @Test
    void MOVED에_시각과_장소가_없으면_원래_값을_쓴다() {
        given(List.of(thursdayClass()),
                List.of(moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25))));

        RoutineOccurrence occurrence = service().expand(USER_ID, WEEK_FROM, WEEK_TO).get(0);

        assertThat(occurrence.startAt().toLocalTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(occurrence.endAt().toLocalTime()).isEqualTo(LocalTime.of(12, 50));
        assertThat(occurrence.location()).isEqualTo("3-315");
    }

    @Test
    void MOVED의_시각과_장소가_있으면_그것을_쓴다() {
        RoutineException exception = moved(1L, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25));
        exception.setMovedStartTime(LocalTime.of(14, 0));
        exception.setMovedEndTime(LocalTime.of(16, 50));
        exception.setMovedLocation("2-201");
        given(List.of(thursdayClass()), List.of(exception));

        RoutineOccurrence occurrence = service().expand(USER_ID, WEEK_FROM, WEEK_TO).get(0);

        assertThat(occurrence.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 14, 0));
        assertThat(occurrence.endAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 16, 50));
        assertThat(occurrence.location()).isEqualTo("2-201");
    }

    // ===== 자정 넘김 =====

    @Test
    void 종료가_시작보다_이르면_다음_날에_끝난다() {
        Routine routine = routine(3L, "알바", null, LocalTime.of(22, 0), LocalTime.of(2, 0),
                SEMESTER_START, null, DayOfWeek.MONDAY);
        given(List.of(routine), List.of());

        RoutineOccurrence occurrence = service().expand(USER_ID, WEEK_FROM, WEEK_TO).get(0);

        assertThat(occurrence.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 22, 0));
        assertThat(occurrence.endAt()).isEqualTo(LocalDateTime.of(2026, 9, 22, 2, 0));
    }

    /** 근무표의 CL = 15~00. 15:00에 시작해 자정에 끝난다. */
    @Test
    void 종료가_자정이면_다음_날_0시에_끝난다() {
        Routine routine = routine(4L, "알바 CL", null, LocalTime.of(15, 0), LocalTime.of(0, 0),
                SEMESTER_START, null, DayOfWeek.MONDAY);
        given(List.of(routine), List.of());

        RoutineOccurrence occurrence = service().expand(USER_ID, WEEK_FROM, WEEK_TO).get(0);

        assertThat(occurrence.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 15, 0));
        assertThat(occurrence.endAt()).isEqualTo(LocalDateTime.of(2026, 9, 22, 0, 0));
    }

    /**
     * 창 시작 전날 22:00에 시작해 창 안으로 이어지는 발생분. 창을 from부터만 훑으면 사라지고,
     * 그러면 월요일 새벽이 비어 있는 것으로 보여 그 시간에 배치가 된다.
     */
    @Test
    void 창_시작_전날에_시작해_창_안으로_이어지면_나온다() {
        // 2026-09-20은 일요일 — 창(월~일) 하루 앞이다.
        Routine routine = routine(5L, "심야 알바", null, LocalTime.of(22, 0), LocalTime.of(2, 0),
                SEMESTER_START, null, DayOfWeek.SUNDAY);
        given(List.of(routine), List.of());

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, WEEK_FROM, WEEK_TO);

        assertThat(occurrences.get(0).startAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 22, 0));
        assertThat(occurrences.get(0).endAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 2, 0));
    }

    /** 같은 일이 이동 목적지에도 일어난다 — 2번 조회도 하루 앞에서부터 봐야 한다. */
    @Test
    void 목적지가_창_시작_전날이어도_자정을_넘겨_이어지면_나온다() {
        Routine routine = routine(6L, "심야 알바", null, LocalTime.of(22, 0), LocalTime.of(2, 0),
                SEMESTER_START, null, DayOfWeek.WEDNESDAY);
        given(List.of(routine),
                List.of(moved(6L, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 20))));

        List<RoutineOccurrence> occurrences = service().expand(USER_ID, WEEK_FROM, WEEK_TO);

        assertThat(occurrences).extracting(RoutineOccurrence::startAt)
                .contains(LocalDateTime.of(2026, 9, 20, 22, 0));
    }

    // ===== 과거 =====

    /** 종료된 루틴도 그 이전 주를 조회하면 나온다. 과거는 남는다. */
    @Test
    void 종료된_루틴도_기간_안의_주를_조회하면_나온다() {
        Routine routine = thursdayClass();
        routine.setEffectiveFrom(LocalDate.of(2026, 3, 2));
        routine.setEffectiveUntil(LocalDate.of(2026, 6, 19));
        given(List.of(routine), List.of());

        // 2026-04-09가 목요일.
        List<RoutineOccurrence> occurrences =
                service().expand(USER_ID, LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 12));

        assertThat(occurrences).extracting(o -> o.startAt().toLocalDate())
                .containsExactly(LocalDate.of(2026, 4, 9));
    }

    // ===== 고정자 =====

    private Routine thursdayClass() {
        Routine routine = routine(1L, "빅데이터분석", 7L, LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_START, SEMESTER_END, DayOfWeek.THURSDAY);
        routine.setLocation("3-315");
        return routine;
    }

    private Routine routine(Long routineId, String title, Long courseId, LocalTime start, LocalTime end,
                            LocalDate from, LocalDate until, DayOfWeek... days) {
        return Routine.builder()
                .routineId(routineId)
                .userId(USER_ID)
                .courseId(courseId)
                .title(title)
                .startTime(start)
                .endTime(end)
                .effectiveFrom(from)
                .effectiveUntil(until)
                .daysOfWeek(new LinkedHashSet<>(Arrays.asList(days)))
                .build();
    }

    private RoutineException skip(Long routineId, LocalDate date) {
        return RoutineException.builder()
                .routineExceptionId(date.toEpochDay())
                .routineId(routineId)
                .exceptionDate(date)
                .type(RoutineExceptionType.SKIP)
                .build();
    }

    private RoutineException moved(Long routineId, LocalDate from, LocalDate to) {
        return RoutineException.builder()
                .routineExceptionId(from.toEpochDay())
                .routineId(routineId)
                .exceptionDate(from)
                .type(RoutineExceptionType.MOVED)
                .movedDate(to)
                .build();
    }

    /**
     * 매퍼 두 개를 한 번에 세운다. 예외 조회는 서비스가 실제로 넘긴 범위로 걸러 돌려준다 —
     * SQL의 BETWEEN과 같은 동작이라, 서비스가 창을 잘못 잡으면 여기서 드러난다.
     */
    private void given(List<Routine> routines, List<RoutineException> exceptions) {
        when(routineMapper.findAllByUserId(eq(USER_ID))).thenReturn(new ArrayList<>(routines));
        if (routines.isEmpty()) {
            return;
        }
        List<RoutineWeekdayRow> weekdayRows = new ArrayList<>();
        for (Routine routine : routines) {
            for (DayOfWeek day : routine.getDaysOfWeek()) {
                RoutineWeekdayRow row = new RoutineWeekdayRow();
                row.setRoutineId(routine.getRoutineId());
                row.setDayOfWeek(day.name());
                weekdayRows.add(row);
            }
            // RoutineReader가 요일을 다시 붙이므로, 고정자가 넣어 둔 값에 기대지 않게 비운다.
            routine.setDaysOfWeek(new LinkedHashSet<>());
        }
        when(routineMapper.findWeekdaysByUserId(eq(USER_ID))).thenReturn(weekdayRows);

        when(routineExceptionMapper.findByUserIdAndExceptionDateRange(eq(USER_ID), any(), any()))
                .thenAnswer(invocation -> inRange(exceptions, invocation.getArgument(1),
                        invocation.getArgument(2), RoutineException::getExceptionDate, false));
        when(routineExceptionMapper.findByUserIdAndMovedDateRange(eq(USER_ID), any(), any()))
                .thenAnswer(invocation -> inRange(exceptions, invocation.getArgument(1),
                        invocation.getArgument(2), RoutineException::getMovedDate, true));
    }

    private List<RoutineException> inRange(List<RoutineException> exceptions, LocalDate from, LocalDate to,
                                           Function<RoutineException, LocalDate> dateOf, boolean movedOnly) {
        List<RoutineException> matched = new ArrayList<>();
        for (RoutineException exception : exceptions) {
            if (movedOnly && exception.getType() != RoutineExceptionType.MOVED) {
                continue;
            }
            LocalDate date = dateOf.apply(exception);
            if (date != null && !date.isBefore(from) && !date.isAfter(to)) {
                matched.add(exception);
            }
        }
        return matched;
    }
}
