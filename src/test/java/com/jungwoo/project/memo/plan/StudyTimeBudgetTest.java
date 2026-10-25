package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusySource;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 시간 범위와 공부량을 나눠 계산한다. 요청량을 줄여 적지 않고, 휴식은 예산상 여유분이다. */
class StudyTimeBudgetTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    private static AvailabilityWindow said(int fromHour, int toHour) {
        return new AvailabilityWindow(DAY.atTime(fromHour, 0), DAY.atTime(toHour, 0), AvailabilitySource.CURRENT_CONVERSATION,
                AvailabilityConfidence.HIGH, "사용자가 말한 공부 시간");
    }

    private static StudyTimeCondition range(int fromHour, int toHour, Integer minutes, String intent) {
        return new StudyTimeCondition(StudyTimeCondition.ADOPTED,
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(fromHour, 0), LocalTime.of(toHour, 0), false, null)),
                List.of(), minutes, minutes == null ? null : "DAY", intent, List.of(), List.of(), List.of());
    }

    @Test
    void 휴식_여유는_60분마다_10분이고_정확히_60분_창은_전부_공부다() {
        assertThat(StudyTimeBudget.studyWithBreaks(60)).isEqualTo(60);
        assertThat(StudyTimeBudget.studyWithBreaks(130)).isEqualTo(120);
        assertThat(StudyTimeBudget.studyWithBreaks(360)).isEqualTo(310);
    }

    @Test
    void 범위만_말하면_범위를_채우되_휴식_여유를_남기고_식사로_나뉜_창은_창마다_계산한다() {
        StudyTimeCondition c = range(14, 20, null, "FILL");
        Scenario.StudyWindows study = StudyTimeBudget.windowsFor(c, DAY, DAY);
        // 18~19 식사로 나뉜 창: 14~18(240분) + 19~20(60분)
        StudyTimeBudget b = StudyTimeBudget.compute(c, study, List.of(said(14, 18), said(19, 20)), List.of(), 1,
                PlanIntensity.NORMAL);
        assertThat(b.capacityMinutes()).isEqualTo(300);
        assertThat(b.requestedMinutes()).isEqualTo(StudyTimeBudget.studyWithBreaks(240) + 60);
        assertThat(b.breakReserveMinutes()).isEqualTo(300 - b.requestedMinutes());
        assertThat(b.targetMinutes()).isEqualTo(b.requestedMinutes());
    }

    @Test
    void 그중_총_4시간이면_그_분량이_목표이고_들어가지_않는_부분은_부족분으로_남는다() {
        StudyTimeCondition c = range(14, 20, 240, "FILL");
        Scenario.StudyWindows study = StudyTimeBudget.windowsFor(c, DAY, DAY);
        StudyTimeBudget fits = StudyTimeBudget.compute(c, study, List.of(said(14, 20)), List.of(), 1, PlanIntensity.NORMAL);
        assertThat(fits.requestedMinutes()).isEqualTo(240);
        assertThat(fits.targetMinutes()).isEqualTo(240);
        assertThat(fits.shortfallMinutes()).isZero();

        // 17시 근무가 범위를 잘라 14~17만 남으면 180분만 들어간다 — 요청량 240은 그대로 두고 부족 60분을 보인다.
        BusyWindow shift = new BusyWindow(DAY.atTime(17, 0), DAY.plusDays(1).atTime(2, 0), "쿠팡알바 근무",
                BusySource.ROUTINE_OCCURRENCE, 220L);
        StudyTimeBudget short_ = StudyTimeBudget.compute(c, study, List.of(said(14, 17)), List.of(shift), 1,
                PlanIntensity.NORMAL);
        assertThat(short_.requestedMinutes()).isEqualTo(240);
        assertThat(short_.capacityMinutes()).isEqualTo(180);
        assertThat(short_.targetMinutes()).isEqualTo(180);
        assertThat(short_.shortfallMinutes()).isEqualTo(60);
        assertThat(short_.conflicts()).containsExactly("17:00~20:00 쿠팡알바 근무");
    }

    @Test
    void 가용_정보만_말했으면_강도로_분량을_정한다() {
        StudyTimeCondition c = range(14, 20, null, "AVAILABLE");
        StudyTimeBudget b = StudyTimeBudget.compute(c, StudyTimeBudget.windowsFor(c, DAY, DAY), List.of(said(14, 20)),
                List.of(), 1, PlanIntensity.NORMAL);
        assertThat(b.requestedMinutes()).isNull();
        assertThat(b.targetMinutes()).isEqualTo(PlanIntensity.NORMAL.targetMinutesFor(360));
    }

    @Test
    void 남는_시간이_0이면_목표도_0이고_요청량은_남는다() {
        StudyTimeCondition c = range(14, 20, 240, "FILL");
        StudyTimeBudget b = StudyTimeBudget.compute(c, StudyTimeBudget.windowsFor(c, DAY, DAY), List.of(), List.of(), 1,
                PlanIntensity.NORMAL);
        assertThat(b.targetMinutes()).isZero();
        assertThat(b.shortfallMinutes()).isEqualTo(240);
    }

    @Test
    void 자정을_넘는_범위는_날짜가_붙은_구간으로_펼친다() {
        StudyTimeCondition c = new StudyTimeCondition(StudyTimeCondition.ADOPTED,
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(22, 0), LocalTime.of(1, 0), true, null)),
                List.of(), null, null, "FILL", List.of(), List.of(), List.of());
        Scenario.StudyWindows study = StudyTimeBudget.windowsFor(c, DAY, DAY);
        assertThat(study.windows()).hasSize(1);
        assertThat(study.windows().get(0).end()).isEqualTo(LocalDateTime.of(2026, 10, 26, 1, 0));
    }
}
