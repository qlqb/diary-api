package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.dto.AvailabilityOverrideRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-08-10(월) 09:00 KST로 고정한 Clock 기준. 이 날짜는 화요일(2026-08-11)이 다음날이 되도록
 * 고른 값이다.
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityEstimateServiceTest {

    private static final Long USER_ID = 1L;
    // 2026-08-10 09:00 Asia/Seoul == 2026-08-10T00:00:00Z
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate HORIZON_START = LocalDate.of(2026, 8, 10); // Monday
    private static final LocalDate HORIZON_END = LocalDate.of(2026, 8, 16); // Sunday

    @Mock
    private ExecutionItemMapper executionItemMapper;

    @Mock
    private RoutineOccurrenceService routineOccurrenceService;

    @Mock
    private CommitmentService commitmentService;

    private AvailabilityEstimateService service;

    @BeforeEach
    void setUp() {
        // defaultTimeZoneId 필드 이니셜라이저가 이미 "Asia/Seoul"이다 — @Value가 적용되지
        // 않는 순수 단위 테스트에서도 기본값으로 동작한다.
        service = new AvailabilityEstimateService(
                executionItemMapper, routineOccurrenceService, commitmentService, FIXED_CLOCK);
    }

    @Test
    void excludesExistingTimeFixedItems_fromAvailabilityWindows() {
        // 기본 창(09:00-23:00) 안에 있는 기존 고정 일정
        ExecutionItem fixed = ExecutionItem.builder()
                .executionItemId(10L).userId(USER_ID)
                .placementType(PlacementType.TIME_FIXED)
                .title("알바")
                .scheduledDate(HORIZON_START)
                .scheduledStartAt(LocalDateTime.of(HORIZON_START, LocalTime.of(20, 0)))
                .scheduledEndAt(LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))
                .build();
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(fixed));

        AvailabilityEstimateResult result = service.estimate(USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        boolean anyWindowOverlapsFixedSlot = result.windows().stream()
                .filter(w -> w.startAt().toLocalDate().equals(HORIZON_START))
                .anyMatch(w -> w.startAt().isBefore(LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))
                        && LocalDateTime.of(HORIZON_START, LocalTime.of(20, 0)).isBefore(w.endAt()));
        assertThat(anyWindowOverlapsFixedSlot).isFalse();
        assertThat(result.busyWindows()).hasSize(1);
    }

    /**
     * 반복 일정 발생분은 기존 TIME_FIXED 조각과 같은 취급이다. 배치가 이걸 보지 못하면
     * 목요일 오후에 "판다스 90분"이 배치되고, 그 순간 계획이 처음부터 거짓이 된다.
     *
     * <p>여기서 expand를 목으로 세우는 것은 전개 규칙을 다시 검증하려는 것이 아니다
     * (그건 RoutineOccurrenceServiceTest의 몫이다). 확인하려는 것은 "expand가 돌려준 것은
     * 무엇이든 그대로 hardBusy가 된다"는 연결이다 — 그래서 종료된 루틴의 기간 내 발생분도
     * 별도 분기 없이 같은 취급을 받는다.
     */
    @Test
    void excludesRoutineOccurrences_fromAvailabilityWindows() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        when(routineOccurrenceService.expand(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(occurrence(LocalTime.of(20, 0), LocalTime.of(21, 0))));

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        assertThat(result.busyWindows())
                .extracting(BusyWindow::label)
                .containsExactly("빅데이터분석");
        assertThat(overlapsMondayEveningSlot(result)).isFalse();
    }

    /**
     * 사용자가 "이 시간은 돼요"라고 표시해도 수업 시간은 다시 열리지 않는다. 재허용은 AI 추론과
     * 사용자 예외에만 적용된다 — 실제 고정 일정에 적용하면 앱이 없는 시간을 배치하게 된다.
     */
    @Test
    void userOverrideCannotReopenRoutineOccurrence() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        when(routineOccurrenceService.expand(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(occurrence(LocalTime.of(20, 0), LocalTime.of(21, 0))));

        AvailabilityOverrideRequest reopen = new AvailabilityOverrideRequest();
        reopen.setStartAt(LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0)));
        reopen.setEndAt(LocalDateTime.of(HORIZON_START, LocalTime.of(22, 0)));
        reopen.setAvailable(true);

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of(reopen));

        assertThat(overlapsMondayEveningSlot(result)).isFalse();
    }

    private RoutineOccurrence occurrence(LocalTime start, LocalTime end) {
        return new RoutineOccurrence(1L, null, "빅데이터분석", "3-315",
                LocalDateTime.of(HORIZON_START, start), LocalDateTime.of(HORIZON_START, end),
                HORIZON_START, false);
    }

    /** 월요일 20:00~21:00과 겹치는 후보 창이 하나라도 남아 있는가. */
    private boolean overlapsMondayEveningSlot(AvailabilityEstimateResult result) {
        LocalDateTime start = LocalDateTime.of(HORIZON_START, LocalTime.of(20, 0));
        LocalDateTime end = LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0));
        return result.windows().stream()
                .anyMatch(w -> w.startAt().isBefore(end) && start.isBefore(w.endAt()));
    }

    @Test
    void excludesAiDeclaredUnavailableWindows_repeatingByDayOfWeek() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        // 화요일 저녁은 알바 -> 매주(이번 horizon 안의 화요일마다) 19:00-22:00은 후보에서 빠져야 한다
        UnavailableWindowSpec tuesdayEvening = new UnavailableWindowSpec(
                null, DayOfWeek.TUESDAY, LocalTime.of(19, 0), LocalTime.of(22, 0), "알바");

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(tuesdayEvening), List.of());

        LocalDate tuesday = LocalDate.of(2026, 8, 11);
        boolean anyWindowOnTuesdayEvening = result.windows().stream()
                .anyMatch(w -> w.startAt().toLocalDate().equals(tuesday)
                        && !w.startAt().isBefore(LocalDateTime.of(tuesday, LocalTime.of(19, 0)))
                        && !w.endAt().isAfter(LocalDateTime.of(tuesday, LocalTime.of(22, 0)))
                        && w.startAt().isBefore(LocalDateTime.of(tuesday, LocalTime.of(22, 0))));
        assertThat(anyWindowOnTuesdayEvening).isFalse();
    }

    @Test
    void userOverride_reopensAiBlockedWindow() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        UnavailableWindowSpec tuesdayEvening = new UnavailableWindowSpec(
                null, DayOfWeek.TUESDAY, LocalTime.of(19, 0), LocalTime.of(22, 0), "알바");
        LocalDate tuesday = LocalDate.of(2026, 8, 11);
        AvailabilityOverrideRequest reopen = AvailabilityOverrideRequest.builder()
                .startAt(LocalDateTime.of(tuesday, LocalTime.of(19, 0)))
                .endAt(LocalDateTime.of(tuesday, LocalTime.of(22, 0)))
                .available(true)
                .build();

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(tuesdayEvening), List.of(reopen));

        boolean anyHighConfidenceWindowOnTuesdayEvening = result.windows().stream()
                .anyMatch(w -> w.startAt().toLocalDate().equals(tuesday)
                        && w.source() == AvailabilitySource.USER_OVERRIDE
                        && w.confidence() == AvailabilityConfidence.HIGH);
        assertThat(anyHighConfidenceWindowOnTuesdayEvening).isTrue();
    }

    @Test
    void fallsBackToLowConfidenceDefaultInference_whenNoOtherSignal() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        assertThat(result.windows()).isNotEmpty();
        assertThat(result.windows()).allSatisfy(w -> {
            assertThat(w.source()).isEqualTo(AvailabilitySource.DEFAULT_INFERENCE);
            assertThat(w.confidence()).isEqualTo(AvailabilityConfidence.LOW);
        });
    }

    @Test
    void neverProducesWindowsBeforeCurrentTime() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 9, 0);
        assertThat(result.windows()).allSatisfy(w -> assertThat(w.startAt()).isAfterOrEqualTo(now));
    }

    // ===== 일회성 약속 =====

    private Commitment commitment(String title, LocalDateTime startAt, LocalDateTime endAt) {
        return Commitment.builder()
                .commitmentId(50L).userId(USER_ID).title(title)
                .startAt(startAt).endAt(endAt)
                .build();
    }

    @Test
    void excludesCommitments_fromAvailabilityWindows() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        // 기본 창(09:00-23:00) 안에 있는 약속
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(commitment("친구 약속",
                        LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0)),
                        LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))));

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        boolean overlaps = result.windows().stream()
                .filter(w -> w.startAt().toLocalDate().equals(HORIZON_START))
                .anyMatch(w -> w.startAt().isBefore(LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))
                        && w.endAt().isAfter(LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0))));
        assertThat(overlaps).isFalse();
        assertThat(result.busyWindows()).extracting(BusyWindow::label).contains("친구 약속");
    }

    @Test
    void userCannotReopenCommitmentTime() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(commitment("면접",
                        LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0)),
                        LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))));

        AvailabilityOverrideRequest reopen = new AvailabilityOverrideRequest();
        reopen.setStartAt(LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0)));
        reopen.setEndAt(LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)));
        reopen.setAvailable(true);

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of(reopen));

        // 루틴과 같은 취급이다 — "이 시간 돼요"라고 말해도 실제 고정 일정은 다시 열리지 않는다.
        boolean overlaps = result.windows().stream()
                .anyMatch(w -> w.startAt().isBefore(LocalDateTime.of(HORIZON_START, LocalTime.of(21, 0)))
                        && w.endAt().isAfter(LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0))));
        assertThat(overlaps).isFalse();
    }

    @Test
    void overlappingRoutineAndCommitment_doNotSubtractTwice() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        LocalDateTime start = LocalDateTime.of(HORIZON_START, LocalTime.of(19, 0));
        LocalDateTime end = LocalDateTime.of(HORIZON_START, LocalTime.of(20, 0));
        when(routineOccurrenceService.expand(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(new RoutineOccurrence(
                        9L, null, "수업", null, start, end, HORIZON_START, false)));
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(commitment("겹치는 약속", start, end)));

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        // 같은 구간을 두 번 빼는 개념이 아니다 — 20:00부터 기본 창 끝까지는 그대로 남아야 한다.
        boolean keepsRemainder = result.windows().stream()
                .anyMatch(w -> w.startAt().equals(end)
                        && w.endAt().equals(LocalDateTime.of(HORIZON_START, LocalTime.of(23, 0))));
        assertThat(keepsRemainder).isTrue();
    }

    // ===== 기본 창 − hardBusy (proposal 1833 회귀) =====

    /** 그날의 후보 창 분 합계. */
    private long candidateMinutesOn(AvailabilityEstimateResult result, LocalDate date) {
        return result.windows().stream()
                .filter(w -> w.startAt().toLocalDate().equals(date))
                .mapToLong(w -> w.durationMinutes())
                .sum();
    }

    private boolean hasWindow(AvailabilityEstimateResult result, LocalDate date, LocalTime from, LocalTime to) {
        return result.windows().stream()
                .anyMatch(w -> w.startAt().equals(LocalDateTime.of(date, from))
                        && w.endAt().equals(LocalDateTime.of(date, to)));
    }

    private boolean overlaps(AvailabilityEstimateResult result, LocalDate date, LocalTime from, LocalTime to) {
        LocalDateTime start = LocalDateTime.of(date, from);
        LocalDateTime end = LocalDateTime.of(date, to);
        return result.windows().stream()
                .anyMatch(w -> w.startAt().isBefore(end) && start.isBefore(w.endAt()));
    }

    /**
     * 수업이 없는 월요일에 저녁 근무만 있으면 낮이 통째로 후보로 남는다.
     *
     * <p>이게 proposal 1833의 회귀 테스트다. 기본 창이 평일 19~22시였을 때는 17~23시 근무가
     * 그 세 시간을 정확히 덮어 월요일 후보가 0분이 됐고, 항목 9개가 전부 주말로 밀렸다.
     * 실제로 비어 있던 낮 여덟 시간은 애초에 후보로 만들어지지도 않았다.
     */
    @Test
    void mondayWithNoClassAndAnEveningShift_keepsItsDaytimeAsCandidate() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(commitment("근무",
                        LocalDateTime.of(HORIZON_START, LocalTime.of(17, 0)),
                        LocalDateTime.of(HORIZON_START, LocalTime.of(23, 0)))));

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        assertThat(candidateMinutesOn(result, HORIZON_START)).isPositive();
        assertThat(hasWindow(result, HORIZON_START, LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .as("낮 09:00~17:00이 후보로 남는다").isTrue();
        assertThat(overlaps(result, HORIZON_START, LocalTime.of(17, 0), LocalTime.of(23, 0)))
                .as("근무 시간은 후보가 아니다").isFalse();
    }

    /**
     * 저녁에 일하는 사용자의 평일이 통째로 사라지지 않는다. 근무표를 붙일수록 평일이 0분이
     * 되던 것이 이 기능의 실패 모드였다.
     */
    @Test
    void everyWeekdayKeepsCandidateTime_whenAllFiveEveningsAreWorked() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        List<Commitment> shifts = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate day = HORIZON_START.plusDays(i);
            LocalTime start = i == 0 ? LocalTime.of(17, 0) : LocalTime.of(18, 0);
            shifts.add(commitment("근무", LocalDateTime.of(day, start), LocalDateTime.of(day, LocalTime.of(23, 0))));
        }
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END)).thenReturn(shifts);

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        for (int i = 0; i < 5; i++) {
            LocalDate day = HORIZON_START.plusDays(i);
            assertThat(candidateMinutesOn(result, day))
                    .as("%s 후보 시간", day.getDayOfWeek()).isPositive();
        }
    }

    /**
     * 기본 창이 넓어져도 수업과 약속은 그대로 빠진다. 넓힌 것은 상한이지 hardBusy 규칙이
     * 아니다 — 이 구분이 무너지면 앱이 수업 시간에 학습을 배치한다.
     */
    @Test
    void classAndCommitment_stillCarveTheDayIntoExactPieces() {
        LocalDate tuesday = LocalDate.of(2026, 8, 11);
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        when(routineOccurrenceService.expand(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(new RoutineOccurrence(2L, null, "자료구조", "3-315",
                        LocalDateTime.of(tuesday, LocalTime.of(14, 0)),
                        LocalDateTime.of(tuesday, LocalTime.of(16, 50)), tuesday, false)));
        when(commitmentService.findOverlapping(USER_ID, HORIZON_START, HORIZON_END))
                .thenReturn(List.of(commitment("근무",
                        LocalDateTime.of(tuesday, LocalTime.of(18, 0)),
                        LocalDateTime.of(tuesday, LocalTime.of(23, 0)))));

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        assertThat(result.windows().stream()
                .filter(w -> w.startAt().toLocalDate().equals(tuesday))
                .map(w -> w.startAt().toLocalTime() + "~" + w.endAt().toLocalTime()))
                .containsExactly("09:00~14:00", "16:50~18:00");
    }

    /** 근거도 일정도 없으면 주말은 기본 창 하나 그대로다. 요일로 창을 나누지 않는다. */
    @Test
    void weekendWithNothingScheduled_isOneFullBaseWindow() {
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(any(), any(), any())).thenReturn(List.of());

        AvailabilityEstimateResult result = service.estimate(
                USER_ID, HORIZON_START, HORIZON_END, List.of(), List.of());

        for (LocalDate weekendDay : List.of(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 16))) {
            assertThat(result.windows().stream()
                    .filter(w -> w.startAt().toLocalDate().equals(weekendDay)))
                    .singleElement()
                    .satisfies(w -> {
                        assertThat(w.startAt().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
                        assertThat(w.endAt().toLocalTime()).isEqualTo(LocalTime.of(23, 0));
                        assertThat(w.confidence()).isEqualTo(AvailabilityConfidence.LOW);
                    });
        }
    }
}
