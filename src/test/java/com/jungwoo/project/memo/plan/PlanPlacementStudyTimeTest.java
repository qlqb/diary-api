package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 상담에서 말한 시간대("20시부터 밤 12시까지") 안에서만 배치 후보를 만든다. */
class PlanPlacementStudyTimeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);

    private static AvailabilityWindow window(LocalDate day, int fromHour, int toHour) {
        return new AvailabilityWindow(day.atTime(fromHour, 0),
                toHour == 24 ? day.plusDays(1).atStartOfDay() : day.atTime(toHour, 0),
                AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본");
    }

    @Test
    void 말한_시간대_밖의_남는_시간은_배치_후보에서_빠진다() {
        List<AvailabilityWindow> windows = List.of(window(DAY, 9, 12), window(DAY, 14, 24), window(DAY.plusDays(1), 9, 24));
        PlanBriefService.TimeBudget evening = new PlanBriefService.TimeBudget(null, true, LocalTime.of(20, 0), "24:00");

        List<AvailabilityWindow> clipped = PlanPlacementService.withinStudyTime(windows, evening);

        assertThat(clipped).extracting(w -> w.startAt() + "~" + w.endAt())
                .containsExactly("2026-10-18T20:00~2026-10-19T00:00", "2026-10-19T20:00~2026-10-20T00:00");
    }

    @Test
    void 끝만_말하면_시작은_기본_창을_따른다() {
        List<AvailabilityWindow> windows = List.of(window(DAY, 14, 24));
        PlanBriefService.TimeBudget untilEleven = new PlanBriefService.TimeBudget(null, true, null, "23:00");

        assertThat(PlanPlacementService.withinStudyTime(windows, untilEleven)).singleElement().satisfies(w -> {
            assertThat(w.startAt()).isEqualTo(DAY.atTime(14, 0));
            assertThat(w.endAt()).isEqualTo(DAY.atTime(23, 0));
        });
    }
}
