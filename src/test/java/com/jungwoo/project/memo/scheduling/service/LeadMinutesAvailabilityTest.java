package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.routine.RoutineExceptionMapper;
import com.jungwoo.project.memo.routine.RoutineMapper;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L7. 이동시간이 가용시간에서 실제로 빠지는지를 전개(실물)와 가용시간 추정(실물)을 이어서
 * 본다. AvailabilityEstimateServiceTest는 expand를 목으로 세우므로 "lead 발생분이 hardBusy가
 * 된다"는 연결을 증명하지 못한다 — 여기서는 매퍼만 목이다.
 *
 * <p>기대값은 숫자를 박지 않는다. 각 날의 candidateMin 감소량은 <b>lead ∩ 그날의 기본 후보
 * 창</b>이다. 기본 창은 서비스 정책이라 여기서 다시 적지 않고, 아무것도 없는 상태로 한 번
 * 추정해 그 결과를 창으로 쓴다(수 09:00 수업의 08:00~09:00 lead는 어느 정책에서도 창 밖이라
 * 0이고, 그 사실은 아래에서 따로 고정한다).
 *
 * <p>이번 주(2026-09-07 월 ~ 09-13 일) 픽스처. 시계는 월요일 00:00 KST로 고정해 "지금 이전"
 * 절단이 요일 비교를 흔들지 않게 한다.
 */
@ExtendWith(MockitoExtension.class)
class LeadMinutesAvailabilityTest {

    private static final Long USER_ID = 1L;
    // 2026-09-07 00:00 Asia/Seoul == 2026-09-06T15:00:00Z
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-06T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 9, 13);
    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 8, 25);
    private static final int LEAD = 60;

    @Mock
    private ExecutionItemMapper executionItemMapper;
    @Mock
    private RoutineMapper routineMapper;
    @Mock
    private RoutineExceptionMapper routineExceptionMapper;
    @Mock
    private CommitmentService commitmentService;

    private RoutineOccurrenceService occurrenceService() {
        return new RoutineOccurrenceService(new RoutineReader(routineMapper), routineExceptionMapper);
    }

    private AvailabilityEstimateService service() {
        return new AvailabilityEstimateService(executionItemMapper, occurrenceService(), commitmentService, FIXED_CLOCK);
    }

    /**
     * 실제 시간표 모양. 수·목·금은 둘째 수업이 있다. 수업 수를 바꿔도 아래 검증은 그대로다 —
     * 기대값을 픽스처에서 계산한다.
     */
    private List<Routine> classes(Integer lead) {
        return List.of(
                routine(1L, "자료구조", LocalTime.of(14, 0), LocalTime.of(17, 0), lead, DayOfWeek.TUESDAY),
                routine(2L, "웹서버", LocalTime.of(9, 0), LocalTime.of(12, 0), lead, DayOfWeek.WEDNESDAY),
                routine(3L, "센서", LocalTime.of(13, 0), LocalTime.of(16, 0), lead, DayOfWeek.WEDNESDAY),
                routine(4L, "빅데이터", LocalTime.of(10, 0), LocalTime.of(11, 0), lead, DayOfWeek.THURSDAY),
                routine(5L, "영어회화", LocalTime.of(12, 0), LocalTime.of(14, 0), lead, DayOfWeek.THURSDAY),
                routine(6L, "스마트앱", LocalTime.of(20, 0), LocalTime.of(22, 0), lead, DayOfWeek.FRIDAY),
                routine(7L, "네트워크", LocalTime.of(22, 0), LocalTime.of(23, 0), lead, DayOfWeek.FRIDAY));
    }

    @Test
    void 요일별_첫_수업_앞에만_lead가_생기고_감소량은_lead와_기본_창의_겹침만큼이다() {
        // 1. 아무 일정도 없는 상태의 창 = 그날의 기본 후보 창.
        given(List.of());
        Map<LocalDate, List<AvailabilityWindow>> baseWindows = byDate(service().estimate(
                USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of()).windows());

        // 2. lead 없이 수업만.
        given(classes(null));
        Map<LocalDate, Long> before = minutesByDate(service().estimate(
                USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of()).windows());

        // 3. lead를 붙여서.
        given(classes(LEAD));
        AvailabilityEstimateResult withLead = service().estimate(USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of());
        Map<LocalDate, Long> after = minutesByDate(withLead.windows());

        // 기대 lead: 픽스처에서 요일별 첫 수업 앞 LEAD분. 숫자를 박지 않고 계산한다.
        Map<LocalDate, RoutineOccurrence> firstClassByDate = new TreeMap<>();
        for (RoutineOccurrence occurrence : occurrenceService().expand(USER_ID, WEEK_FROM, WEEK_TO)) {
            if (occurrence.lead()) {
                continue;
            }
            firstClassByDate.merge(occurrence.startAt().toLocalDate(), occurrence,
                    (a, b) -> a.startAt().isBefore(b.startAt()) ? a : b);
        }
        assertThat(firstClassByDate).isNotEmpty();

        List<BusyWindow> leads = withLead.busyWindows().stream().filter(w -> w.label().endsWith(" 이동")).toList();
        assertThat(leads).hasSize(firstClassByDate.size());
        for (RoutineOccurrence first : firstClassByDate.values()) {
            LocalDateTime leadStart = first.startAt().minusMinutes(LEAD);
            assertThat(leads).as("%s의 첫 수업 %s", first.startAt().toLocalDate(), first.title())
                    .anyMatch(w -> w.label().equals(first.title() + " 이동")
                            && w.startAt().equals(leadStart) && w.endAt().equals(first.startAt()));
        }

        for (LocalDate date = WEEK_FROM; !date.isAfter(WEEK_TO); date = date.plusDays(1)) {
            long expectedDrop = 0;
            RoutineOccurrence first = firstClassByDate.get(date);
            if (first != null) {
                expectedDrop = overlapMinutes(first.startAt().minusMinutes(LEAD), first.startAt(),
                        baseWindows.getOrDefault(date, List.of()));
            }
            assertThat(before.getOrDefault(date, 0L) - after.getOrDefault(date, 0L))
                    .as("%s(%s) candidateMin 감소", date, date.getDayOfWeek()).isEqualTo(expectedDrop);
        }
    }

    /** 수 09:00 수업의 lead(08:00~09:00)는 기본 창 밖이다. 발생분은 60분 있지만 candidateMin은 그대로다. */
    @Test
    void 기본_창_밖의_lead는_발생분은_있지만_후보시간을_줄이지_않는다() {
        LocalDate wednesday = LocalDate.of(2026, 9, 9);
        given(List.of(routine(2L, "웹서버", LocalTime.of(9, 0), LocalTime.of(12, 0), null, DayOfWeek.WEDNESDAY)));
        long before = minutesByDate(service().estimate(USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of()).windows())
                .getOrDefault(wednesday, 0L);

        given(List.of(routine(2L, "웹서버", LocalTime.of(9, 0), LocalTime.of(12, 0), LEAD, DayOfWeek.WEDNESDAY)));
        AvailabilityEstimateResult result = service().estimate(USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of());

        assertThat(result.busyWindows()).extracting(BusyWindow::label, BusyWindow::startAt, BusyWindow::endAt)
                .contains(org.assertj.core.groups.Tuple.tuple("웹서버 이동",
                        LocalDateTime.of(2026, 9, 9, 8, 0), LocalDateTime.of(2026, 9, 9, 9, 0)));
        assertThat(minutesByDate(result.windows()).getOrDefault(wednesday, 0L)).isEqualTo(before);
    }

    /**
     * 약속이 lead와 겹쳐도 lead는 억제되지 않고, 시간은 합집합으로 한 번만 빠진다. 금 20:00
     * 수업(lead 19:00~20:00)과 18:30~19:30 약속: 19:00~19:30이 둘 다에 걸치지만 두 번 빠지지 않는다.
     */
    @Test
    void 약속이_lead와_겹치면_억제하지_않고_합집합으로_한_번만_뺀다() {
        LocalDate friday = LocalDate.of(2026, 9, 11);
        given(List.of(routine(6L, "스마트앱", LocalTime.of(20, 0), LocalTime.of(22, 0), LEAD, DayOfWeek.FRIDAY)));
        when(commitmentService.findOverlapping(eq(USER_ID), any(), any())).thenReturn(List.of(
                Commitment.builder().commitmentId(9L).userId(USER_ID).title("병원")
                        .startAt(LocalDateTime.of(2026, 9, 11, 18, 30))
                        .endAt(LocalDateTime.of(2026, 9, 11, 19, 30))
                        .build()));

        AvailabilityEstimateResult result = service().estimate(USER_ID, WEEK_FROM, WEEK_TO, List.of(), List.of());

        assertThat(result.busyWindows()).extracting(BusyWindow::label)
                .contains("스마트앱 이동", "병원", "스마트앱");
        // 금요일 창 중 18:30~22:00은 전부 막혀 있고, 그 밖은 열려 있다.
        for (AvailabilityWindow window : result.windows()) {
            if (!window.startAt().toLocalDate().equals(friday)) {
                continue;
            }
            boolean overlapsBlocked = window.startAt().isBefore(LocalDateTime.of(2026, 9, 11, 22, 0))
                    && LocalDateTime.of(2026, 9, 11, 18, 30).isBefore(window.endAt());
            assertThat(overlapsBlocked).as("%s", window).isFalse();
        }
    }

    // ===== 고정자 =====

    private Routine routine(Long routineId, String title, LocalTime start, LocalTime end,
                            Integer leadMinutes, DayOfWeek... days) {
        Set<DayOfWeek> daysOfWeek = new LinkedHashSet<>(List.of(days));
        return Routine.builder()
                .routineId(routineId)
                .userId(USER_ID)
                .courseId(routineId)
                .title(title)
                .startTime(start)
                .endTime(end)
                .leadMinutes(leadMinutes)
                .effectiveFrom(SEMESTER_START)
                .daysOfWeek(daysOfWeek)
                .build();
    }

    private void given(List<Routine> routines) {
        List<RoutineWeekdayRow> rows = new ArrayList<>();
        for (Routine routine : routines) {
            for (DayOfWeek day : routine.getDaysOfWeek()) {
                RoutineWeekdayRow row = new RoutineWeekdayRow();
                row.setRoutineId(routine.getRoutineId());
                row.setDayOfWeek(day.name());
                rows.add(row);
            }
        }
        when(routineMapper.findAllByUserId(USER_ID)).thenReturn(new ArrayList<>(routines));
        org.mockito.Mockito.lenient().when(routineMapper.findWeekdaysByUserId(USER_ID)).thenReturn(rows);
        org.mockito.Mockito.lenient().when(routineExceptionMapper.findByUserIdAndExceptionDateRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(routineExceptionMapper.findByUserIdAndMovedDateRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(commitmentService.findOverlapping(eq(USER_ID), any(), any())).thenReturn(List.of());
    }

    private static Map<LocalDate, List<AvailabilityWindow>> byDate(List<AvailabilityWindow> windows) {
        Map<LocalDate, List<AvailabilityWindow>> result = new HashMap<>();
        for (AvailabilityWindow window : windows) {
            result.computeIfAbsent(window.startAt().toLocalDate(), key -> new ArrayList<>()).add(window);
        }
        return result;
    }

    private static Map<LocalDate, Long> minutesByDate(List<AvailabilityWindow> windows) {
        Map<LocalDate, Long> minutes = new HashMap<>();
        for (AvailabilityWindow window : windows) {
            minutes.merge(window.startAt().toLocalDate(),
                    Duration.between(window.startAt(), window.endAt()).toMinutes(), Long::sum);
        }
        return minutes;
    }

    private static long overlapMinutes(LocalDateTime start, LocalDateTime end, List<AvailabilityWindow> windows) {
        long total = 0;
        for (AvailabilityWindow window : windows) {
            LocalDateTime s = start.isAfter(window.startAt()) ? start : window.startAt();
            LocalDateTime e = end.isBefore(window.endAt()) ? end : window.endAt();
            if (s.isBefore(e)) {
                total += Duration.between(s, e).toMinutes();
            }
        }
        return total;
    }
}
