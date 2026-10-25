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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 가정 상태 전개. 실제 데이터 모양(쿠팡알바 근무 17:00~02:00 매일, 이동시간 15:30~17:00 매일)으로 본다 —
 * 자정을 넘는 회차를 (routineId, 원래 날짜)로 가리켜 빼는 것이 이 기능의 핵심 경우다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutineOverlayTest {

    private static final Long USER = 1L;
    private static final LocalDate SUN = LocalDate.of(2026, 10, 25);
    private static final LocalDate MON = SUN.plusDays(1);

    @Mock
    private RoutineMapper routineMapper;
    @Mock
    private RoutineExceptionMapper exceptionMapper;

    private RoutineOccurrenceService service(List<Routine> routines, List<RoutineException> exceptions) {
        when(routineMapper.findAllByUserId(eq(USER))).thenReturn(new ArrayList<>(routines));
        List<RoutineWeekdayRow> rows = new ArrayList<>();
        for (Routine r : routines) {
            for (DayOfWeek d : r.getDaysOfWeek()) {
                RoutineWeekdayRow row = new RoutineWeekdayRow();
                row.setRoutineId(r.getRoutineId());
                row.setDayOfWeek(d.name());
                rows.add(row);
            }
            r.setDaysOfWeek(new LinkedHashSet<>());
        }
        when(routineMapper.findWeekdaysByUserId(eq(USER))).thenReturn(rows);
        when(exceptionMapper.findByUserIdAndExceptionDateRange(eq(USER), any(), any())).thenAnswer(inv -> exceptions.stream()
                .filter(e -> !e.getExceptionDate().isBefore(inv.getArgument(1)) && !e.getExceptionDate().isAfter(inv.getArgument(2)))
                .toList());
        when(exceptionMapper.findByUserIdAndMovedDateRange(eq(USER), any(), any())).thenAnswer(inv -> exceptions.stream()
                .filter(e -> e.getMovedDate() != null && !e.getMovedDate().isBefore(inv.getArgument(1))
                        && !e.getMovedDate().isAfter(inv.getArgument(2)))
                .toList());
        return new RoutineOccurrenceService(new RoutineReader(routineMapper), exceptionMapper);
    }

    private static Routine daily(long id, String title, LocalTime start, LocalTime end) {
        return Routine.builder().routineId(id).userId(USER).title(title).startTime(start).endTime(end)
                .effectiveFrom(LocalDate.of(2026, 9, 13)).daysOfWeek(new LinkedHashSet<>(EnumSet.allOf(DayOfWeek.class)))
                .build();
    }

    private static List<Routine> coupang() {
        return List.of(daily(220, "쿠팡알바 근무", LocalTime.of(17, 0), LocalTime.of(2, 0)),
                daily(218, "쿠팡알바 이동시간", LocalTime.of(15, 30), LocalTime.of(17, 0)));
    }

    private static RoutineException skip(long routineId, LocalDate date) {
        return RoutineException.builder().routineExceptionId(-1L).routineId(routineId).exceptionDate(date)
                .type(RoutineExceptionType.SKIP).build();
    }

    @Test
    void 겹침이_없으면_원래_전개와_같다() {
        RoutineOccurrenceService svc = service(coupang(), List.of());
        assertThat(svc.expand(USER, SUN, MON, RoutineOverlay.NONE)).isEqualTo(svc.expand(USER, SUN, MON));
    }

    @Test
    void 오늘_근무_회차만_빼면_이동시간과_다음_날_근무는_그대로다() {
        RoutineOccurrenceService svc = service(coupang(), List.of());
        RoutineOverlay overlay = RoutineOverlay.builder().putException(skip(220, SUN)).build();

        List<RoutineOccurrence> out = svc.expand(USER, SUN, MON, overlay);

        assertThat(out).noneMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));
        assertThat(out).anyMatch(o -> o.routineId() == 218 && o.sourceDate().equals(SUN));
        assertThat(out).anyMatch(o -> o.routineId() == 220 && o.sourceDate().equals(MON)
                && o.endAt().equals(LocalDateTime.of(2026, 10, 27, 2, 0)));
        // 원본 전개는 바뀌지 않는다.
        assertThat(svc.expand(USER, SUN, MON)).anyMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));
    }

    @Test
    void 전날_회차를_빼면_자정_넘어_이어지던_새벽_부분도_사라진다() {
        RoutineOccurrenceService svc = service(coupang(), List.of());
        RoutineOverlay overlay = RoutineOverlay.builder().putException(skip(220, SUN)).build();

        // 월요일만 보는 창: 일요일 17:00~월 02:00 회차가 창 안으로 이어지지만 빠져야 한다.
        assertThat(svc.expand(USER, MON, MON, overlay))
                .noneMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));
        assertThat(svc.expand(USER, MON, MON))
                .anyMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));
    }

    @Test
    void 예외를_지우는_겹침은_원래_회차를_돌려준다() {
        RoutineOccurrenceService svc = service(coupang(), List.of(skip(220, SUN)));
        assertThat(svc.expand(USER, SUN, SUN)).noneMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));

        RoutineOverlay restore = RoutineOverlay.builder().removeException(220L, SUN).build();
        assertThat(svc.expand(USER, SUN, SUN, restore)).anyMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN));
    }

    @Test
    void 이동_겹침은_원래_날을_비우고_목적지에_나온다() {
        RoutineOccurrenceService svc = service(coupang(), List.of());
        RoutineException moved = RoutineException.builder().routineExceptionId(-2L).routineId(220L).exceptionDate(SUN)
                .type(RoutineExceptionType.MOVED).movedDate(SUN.plusDays(2))
                .movedStartTime(LocalTime.of(19, 0)).movedEndTime(LocalTime.of(23, 0)).build();
        RoutineOverlay overlay = RoutineOverlay.builder().putException(moved).build();

        List<RoutineOccurrence> out = svc.expand(USER, SUN, SUN.plusDays(2), overlay);

        assertThat(out).noneMatch(o -> o.routineId() == 220 && o.sourceDate().equals(SUN) && !o.moved());
        assertThat(out).anyMatch(o -> o.routineId() == 220 && o.moved()
                && o.startAt().equals(LocalDateTime.of(2026, 10, 27, 19, 0)));
    }

    @Test
    void 새_규칙은_더하고_삭제한_규칙은_뺀다() {
        RoutineOccurrenceService svc = service(coupang(), List.of());
        Routine removed = daily(218, "쿠팡알바 이동시간", LocalTime.of(15, 30), LocalTime.of(17, 0));
        removed.setDeleted(true);
        RoutineOverlay overlay = RoutineOverlay.builder()
                .putRoutine(removed)
                .putRoutine(daily(-1, "새 스터디", LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .build();

        List<RoutineOccurrence> out = svc.expand(USER, SUN, SUN, overlay);

        assertThat(out).noneMatch(o -> o.routineId() == 218);
        assertThat(out).anyMatch(o -> o.routineId() == -1 && o.title().equals("새 스터디"));
    }
}
