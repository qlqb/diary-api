package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.ai.brief.StudyTimeInterpreter;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 말한 시간 조건으로 정한 이번 계획의 분량. 세 값을 나눠 둔다 — 사용자가 요청한 양(requested), 그 범위에 실제로 들어갈 수 있는 양
 * (capacity), 이번 초안이 목표로 하는 양(target). 들어가지 않는다고 요청량을 줄여 적지 않는다(부족분은 shortfall로 보인다).
 *
 * <p>범위만 말하고 분량을 말하지 않았으면 그 범위를 공부로 채우되 휴식 여유를 남긴다 — 연속 60분마다 10분. 이것은 예산에서 미리 남기는
 * 여유분이지 배치가 그 간격을 보장한다는 뜻이 아니다(배치의 간격은 기존 SOFT 선호다). 화면 문구도 "휴식 여유 N분"이다.
 *
 * @param requestedMinutes 사용자가 말했거나 범위에서 계산한 요청량. 가용 정보만 말했으면 null(강도 기준)
 * @param capacityMinutes  말한 범위 안에서 실제 일정·제외 구간을 뺀 남는 시간(범위가 없으면 전체 남는 시간)
 * @param conflicts        말한 범위와 겹친 등록 일정("17:00~20:00 쿠팡알바 근무")
 */
public record StudyTimeBudget(Integer requestedMinutes, int capacityMinutes, int targetMinutes, int breakReserveMinutes,
                              List<String> conflicts) {

    public static final int BREAK_EVERY_MINUTES = 60;
    public static final int BREAK_MINUTES = 10;

    /** 요청량이 남는 시간보다 크면 그 차이. */
    public int shortfallMinutes() {
        return requestedMinutes == null ? 0 : Math.max(0, requestedMinutes - capacityMinutes);
    }

    /** 조건을 계획 날짜들의 실제 시각으로 펼친다. 범위가 없으면 null. */
    public static Scenario.StudyWindows windowsFor(StudyTimeCondition condition, LocalDate start, LocalDate end) {
        if (condition == null || !condition.adopted() && !StudyTimeCondition.AMBIGUOUS.equals(condition.status())
                || (condition.windows().isEmpty() && condition.exclusions().isEmpty())) {
            return null;
        }
        List<Scenario.TimeRange> windows = new ArrayList<>();
        List<Scenario.TimeRange> exclusions = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            for (StudyTimeCondition.Range r : condition.windows()) {
                LocalDateTime[] on = r.on(day, StudyTimeInterpreter.DEFAULT_START);
                if (on != null) {
                    windows.add(new Scenario.TimeRange(on[0], on[1]));
                }
            }
            for (StudyTimeCondition.Range r : condition.exclusions()) {
                LocalDateTime[] on = r.on(day, StudyTimeInterpreter.DEFAULT_START);
                if (on != null) {
                    exclusions.add(new Scenario.TimeRange(on[0], on[1]));
                }
            }
        }
        return new Scenario.StudyWindows(windows, exclusions);
    }

    /**
     * @param windows 말한 범위로 계산한 남는 시간({@code Scenario.withStudy}로 구한 결과)
     * @param busy    같은 계산의 실제 일정
     */
    public static StudyTimeBudget compute(StudyTimeCondition condition, Scenario.StudyWindows study,
                                          List<AvailabilityWindow> windows, List<BusyWindow> busy, int days,
                                          PlanIntensity intensity) {
        boolean ranged = study != null && !study.windows().isEmpty();
        List<AvailabilityWindow> inRange = ranged
                ? windows.stream().filter(w -> w.source() == AvailabilitySource.CURRENT_CONVERSATION).toList()
                : windows;
        int capacity = 0;
        int fill = 0;
        for (AvailabilityWindow w : inRange) {
            int minutes = (int) Duration.between(w.startAt(), w.endAt()).toMinutes();
            capacity += minutes;
            fill += studyWithBreaks(minutes);
        }
        Integer total = condition == null ? null : condition.totalFor(days);
        Integer requested;
        int breakReserve = 0;
        if (total != null) {
            requested = total;
        } else if (ranged && !"AVAILABLE".equals(condition.intent())) {
            requested = fill;
            breakReserve = capacity - fill;
        } else {
            requested = null;
        }
        int target = requested == null ? intensity.targetMinutesFor(capacity) : Math.min(requested, capacity);
        List<String> conflicts = new ArrayList<>();
        if (ranged) {
            for (Scenario.TimeRange range : study.windows()) {
                for (BusyWindow b : busy) {
                    LocalDateTime from = b.startAt().isAfter(range.start()) ? b.startAt() : range.start();
                    LocalDateTime to = b.endAt().isBefore(range.end()) ? b.endAt() : range.end();
                    if (from.isBefore(to)) {
                        conflicts.add(from.toLocalTime() + "~" + (to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                                ? "24:00" : to.toLocalTime()) + " " + b.label());
                    }
                }
            }
        }
        return new StudyTimeBudget(requested, capacity, Math.max(0, target), breakReserve, conflicts);
    }

    /** 연속 W분에 넣을 수 있는 공부 분(60분마다 10분 휴식, 5분 단위). 정확히 60분이면 60분 전부다(뒤에 공부가 없으면 휴식이 필요 없다). */
    static int studyWithBreaks(int windowMinutes) {
        int best = 0;
        for (int s = 0; s <= windowMinutes; s += 5) {
            int breaks = s <= 0 ? 0 : ((s - 1) / BREAK_EVERY_MINUTES) * BREAK_MINUTES;
            if (s + breaks <= windowMinutes) {
                best = s;
            }
        }
        return best;
    }
}
