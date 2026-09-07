package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.dto.AvailabilityOverrideRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 이번 계획에 실제로 쓸 수 있는 후보 시간을 추정한다. 단순히 "캘린더가 비어 있다"가 아니라
 * 출처와 신뢰도, 이유를 함께 만든다.
 *
 * 판단 순서:
 * 1. 기존 TIME_FIXED 실행 조각, 그리고 반복 일정(수업·알바)의 발생분과 겹치는 시간은 항상
 *    제외한다(강한 조건, 사용자 재허용 불가).
 * 2. 현재 대화에서 사용자가 명시한 사용 불가 시간을 제외한다(AI_INFERRED 강한 조건).
 * 3. 사용자가 미리보기에서 직접 고친 예외를 반영한다 — 막거나(available=false) 다시
 *    열거나(available=true, 단 1번 TIME_FIXED는 재허용 대상이 아니다).
 * 4. 남는 근거가 없으면 Asia/Seoul 기준 보수적 기본 활동 시간대를 LOW 신뢰도로 채운다.
 * 5. 현재 시각 이전과 계획 범위 밖은 항상 제외한다.
 *
 * ContextItem(장기 확정 컨텍스트)과 실행 패턴 집계는 아직 이 코드베이스에 없다 — 출처
 * enum(USER_CONFIRMED_CONTEXT/EXECUTION_PATTERN) 계약만 남기고 이번 구현에서는 만들지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityEstimateService {

    private static final LocalTime WEEKDAY_DEFAULT_START = LocalTime.of(19, 0);
    private static final LocalTime WEEKDAY_DEFAULT_END = LocalTime.of(22, 0);
    private static final LocalTime WEEKEND_DEFAULT_START = LocalTime.of(10, 0);
    private static final LocalTime WEEKEND_DEFAULT_END = LocalTime.of(18, 0);
    private static final Set<DayOfWeek> WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final ExecutionItemMapper executionItemMapper;
    private final RoutineOccurrenceService routineOccurrenceService;
    private final CommitmentService commitmentService;
    private final Clock clock;

    @Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    public AvailabilityEstimateResult estimate(
            Long userId, LocalDate horizonStart, LocalDate horizonEnd,
            List<UnavailableWindowSpec> aiUnavailable, List<AvailabilityOverrideRequest> userOverrides
    ) {
        ZoneId zone = ZoneId.of(defaultTimeZoneId);
        LocalDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDateTime();
        LocalDateTime lowerBound = now.isAfter(horizonStart.atStartOfDay()) ? now : horizonStart.atStartOfDay();
        LocalDateTime upperBound = horizonEnd.plusDays(1).atStartOfDay();

        List<ExecutionItem> existingFixed =
                executionItemMapper.findTimeFixedByUserIdAndDateRange(userId, horizonStart, horizonEnd);
        List<Interval> hardBusy = new ArrayList<>();
        List<BusyWindow> busyWindows = new ArrayList<>();
        for (ExecutionItem item : existingFixed) {
            hardBusy.add(new Interval(item.getScheduledStartAt(), item.getScheduledEndAt()));
            busyWindows.add(new BusyWindow(item.getScheduledStartAt(), item.getScheduledEndAt(), item.getTitle()));
        }

        /*
         * 반복 일정 발생분은 기존 TIME_FIXED 조각과 완전히 같은 취급이다 — hardBusy이지
         * softBlocked가 아니다. softBlocked는 AI가 대화에서 추론한 것과 사용자가 미리보기에서
         * 고친 예외를 담고 available=true로 다시 열리는데, 수업 시간은 재허용 대상이 아니다.
         *
         * 화면(GET /api/routines/occurrences)과 여기가 같은 expand를 부른다. 그래서 "화면에는
         * 보이는데 배치는 그 시간에 학습을 넣는" 상태가 구조적으로 불가능하다.
         */
        /*
         * 이동시간(lead) 발생분도 expand가 돌려주는 그대로 hardBusy다. 여기서 lead를 따로
         * 거르지 않는 것이 요점이다 — 시간을 막는 것이 그 값의 전부이고, 화면과 같은 목록을
         * 본다. 관측을 위해 lead 구간만 따로 세어 둔다.
         */
        List<Interval> leadBusy = new ArrayList<>();
        for (RoutineOccurrence occurrence :
                routineOccurrenceService.expand(userId, horizonStart, horizonEnd)) {
            hardBusy.add(new Interval(occurrence.startAt(), occurrence.endAt()));
            busyWindows.add(new BusyWindow(occurrence.startAt(), occurrence.endAt(), occurrence.title()));
            if (occurrence.lead()) {
                leadBusy.add(new Interval(occurrence.startAt(), occurrence.endAt()));
            }
        }

        /*
         * 일회성 약속도 같은 자리에 들어온다 — 반복이냐 한 번이냐만 다르고 "그 시간은 못
         * 쓴다"는 의미는 같다. 새 제약을 만들지 않고 이미 있는 hardBusy 입력이 하나 느는
         * 것뿐이다. 루틴과 약속이 겹쳐도 시간을 두 번 빼지 않는다 — 아래 subtract가
         * 구간 단위로 깎으므로 겹친 부분은 한 번만 사라진다.
         */
        for (Commitment commitment : commitmentService.findOverlapping(userId, horizonStart, horizonEnd)) {
            hardBusy.add(new Interval(commitment.getStartAt(), commitment.getEndAt()));
            busyWindows.add(new BusyWindow(
                    commitment.getStartAt(), commitment.getEndAt(), commitment.getTitle()));
        }

        List<Interval> softBlocked = new ArrayList<>();
        if (aiUnavailable != null) {
            for (UnavailableWindowSpec spec : aiUnavailable) {
                for (LocalDate date : expandDates(spec, horizonStart, horizonEnd)) {
                    softBlocked.add(new Interval(LocalDateTime.of(date, spec.startTime()), LocalDateTime.of(date, spec.endTime())));
                }
            }
        }

        List<Interval> reopenIntervals = new ArrayList<>();
        if (userOverrides != null) {
            for (AvailabilityOverrideRequest override : userOverrides) {
                if (override.getStartAt() == null || override.getEndAt() == null) {
                    continue;
                }
                Interval interval = new Interval(override.getStartAt(), override.getEndAt());
                if (override.isAvailable()) {
                    reopenIntervals.add(interval);
                } else {
                    softBlocked.add(interval);
                }
            }
        }

        // available=true 재허용은 AI/사용자 unavailable에만 적용한다 — 실제 고정 일정(1번)은
        // 사용자가 "이 시간은 돼요"라고 말해도 다시 열리지 않는다.
        List<Interval> softBlockedAfterReopen = subtractAll(softBlocked, reopenIntervals);

        List<AvailabilityWindow> rawWindows = defaultInferenceWindows(horizonStart, horizonEnd);

        List<Interval> allExclusions = new ArrayList<>(hardBusy);
        allExclusions.addAll(softBlockedAfterReopen);

        List<AvailabilityWindow> resultWindows = new ArrayList<>();
        for (AvailabilityWindow raw : rawWindows) {
            for (Interval remaining : subtract(new Interval(raw.startAt(), raw.endAt()), allExclusions)) {
                Interval clipped = clip(remaining, lowerBound, upperBound);
                if (clipped != null) {
                    resultWindows.add(new AvailabilityWindow(
                            clipped.start(), clipped.end(), raw.source(), raw.confidence(), raw.reason()));
                }
            }
        }
        for (Interval reopen : reopenIntervals) {
            for (Interval piece : subtract(reopen, hardBusy)) {
                Interval clipped = clip(piece, lowerBound, upperBound);
                if (clipped != null) {
                    resultWindows.add(new AvailabilityWindow(clipped.start(), clipped.end(),
                            AvailabilitySource.USER_OVERRIDE, AvailabilityConfidence.HIGH,
                            "사용자가 다시 가능하다고 표시한 시간"));
                }
            }
        }

        resultWindows.sort((a, b) -> a.startAt().compareTo(b.startAt()));
        logPerDay(userId, rawWindows, resultWindows, hardBusy, leadBusy);
        return new AvailabilityEstimateResult(resultWindows, busyWindows);
    }

    /**
     * 요일별 관측 로그. candidateMin은 최종 후보 창의 합, hardBusyMin·leadMin은 기본 창
     * 안과 겹치는 부분만 센다 — 창 밖의 수업(오후 수업과 평일 저녁 창)을 세면 hardBusyMin이
     * 그날의 후보 시간보다 커져 숫자가 서로를 설명하지 못한다.
     */
    private void logPerDay(Long userId, List<AvailabilityWindow> rawWindows,
                           List<AvailabilityWindow> resultWindows,
                           List<Interval> hardBusy, List<Interval> leadBusy) {
        if (!log.isInfoEnabled()) {
            return;
        }
        for (AvailabilityWindow raw : rawWindows) {
            Interval base = new Interval(raw.startAt(), raw.endAt());
            LocalDate day = raw.startAt().toLocalDate();
            long candidateMin = 0;
            for (AvailabilityWindow window : resultWindows) {
                if (window.startAt().toLocalDate().equals(day)) {
                    candidateMin += java.time.Duration.between(window.startAt(), window.endAt()).toMinutes();
                }
            }
            log.info("availability.estimate userId={} day={} date={} candidateMin={} hardBusyMin={} leadMin={}",
                    userId, day.getDayOfWeek(), day, candidateMin,
                    overlapMinutes(base, hardBusy), overlapMinutes(base, leadBusy));
        }
    }

    /** base와 겹치는 cuts의 합(분). 겹친 구간끼리 또 겹치면 한 번만 센다. */
    private static long overlapMinutes(Interval base, List<Interval> cuts) {
        long total = 0;
        for (Interval remaining : subtract(base, cuts)) {
            total += java.time.Duration.between(remaining.start(), remaining.end()).toMinutes();
        }
        return java.time.Duration.between(base.start(), base.end()).toMinutes() - total;
    }

    private List<LocalDate> expandDates(UnavailableWindowSpec spec, LocalDate horizonStart, LocalDate horizonEnd) {
        List<LocalDate> dates = new ArrayList<>();
        if (spec.date() != null) {
            if (!spec.date().isBefore(horizonStart) && !spec.date().isAfter(horizonEnd)) {
                dates.add(spec.date());
            }
            return dates;
        }
        if (spec.dayOfWeek() != null) {
            for (LocalDate d = horizonStart; !d.isAfter(horizonEnd); d = d.plusDays(1)) {
                if (d.getDayOfWeek() == spec.dayOfWeek()) {
                    dates.add(d);
                }
            }
        }
        return dates;
    }

    private List<AvailabilityWindow> defaultInferenceWindows(LocalDate horizonStart, LocalDate horizonEnd) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (LocalDate d = horizonStart; !d.isAfter(horizonEnd); d = d.plusDays(1)) {
            boolean weekend = WEEKEND.contains(d.getDayOfWeek());
            LocalTime start = weekend ? WEEKEND_DEFAULT_START : WEEKDAY_DEFAULT_START;
            LocalTime end = weekend ? WEEKEND_DEFAULT_END : WEEKDAY_DEFAULT_END;
            windows.add(new AvailabilityWindow(
                    LocalDateTime.of(d, start), LocalDateTime.of(d, end),
                    AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW,
                    "구체적인 근거가 없어 Asia/Seoul 기준 보수적인 기본 활동 시간대를 썼어요"));
        }
        return windows;
    }

    // ===== 구간 연산 =====

    private record Interval(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(Interval other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    private static Interval clip(Interval interval, LocalDateTime lower, LocalDateTime upper) {
        if (interval == null) {
            return null;
        }
        LocalDateTime start = interval.start().isBefore(lower) ? lower : interval.start();
        LocalDateTime end = interval.end().isAfter(upper) ? upper : interval.end();
        return start.isBefore(end) ? new Interval(start, end) : null;
    }

    /** base 하나에서 cuts 전체를 뺀 나머지 구간들. */
    private static List<Interval> subtract(Interval base, List<Interval> cuts) {
        List<Interval> remaining = new ArrayList<>();
        remaining.add(base);
        for (Interval cut : cuts) {
            List<Interval> next = new ArrayList<>();
            for (Interval r : remaining) {
                next.addAll(subtractOne(r, cut));
            }
            remaining = next;
        }
        return remaining;
    }

    /** base 목록 전체에서 cuts 전체를 뺀 나머지. */
    private static List<Interval> subtractAll(List<Interval> bases, List<Interval> cuts) {
        List<Interval> result = new ArrayList<>();
        for (Interval base : bases) {
            result.addAll(subtract(base, cuts));
        }
        return result;
    }

    private static List<Interval> subtractOne(Interval base, Interval cut) {
        if (!base.overlaps(cut)) {
            return List.of(base);
        }
        List<Interval> pieces = new ArrayList<>();
        if (cut.start().isAfter(base.start())) {
            pieces.add(new Interval(base.start(), cut.start()));
        }
        if (cut.end().isBefore(base.end())) {
            pieces.add(new Interval(cut.end(), base.end()));
        }
        return pieces;
    }
}
