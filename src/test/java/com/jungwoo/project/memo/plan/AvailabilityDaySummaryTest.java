package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityDaySummaryTest {

    private static final LocalDate SAT = LocalDate.of(2026, 9, 12);
    private static final LocalDate SUN = SAT.plusDays(1);

    @Test
    @DisplayName("자정을 넘는 근무는 두 날짜로 갈라진다 — 실데이터의 토·일 17:00~02:00")
    void overnightShiftIsSplitAcrossDays() {
        AvailabilityEstimateResult result = new AvailabilityEstimateResult(
                List.of(),
                List.of(new BusyWindow(SAT.atTime(17, 0), SUN.atTime(2, 0), "쿠팡알바 근무")));

        List<String> lines = AvailabilityDaySummary.format(SAT, SUN, result);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .as("토요일 몫은 자정까지")
                .contains("9/12(토)")
                .contains("17:00~24:00 쿠팡알바 근무");
        assertThat(lines.get(1))
                .as("일요일 새벽이 비어 보이면 안 된다")
                .contains("9/13(일)")
                .contains("00:00~02:00 쿠팡알바 근무");
    }

    @Test
    @DisplayName("남는 시간이 없는 날도 한 줄을 남긴다 — 비어 있다는 것도 정보다")
    void emptyDayStillGetsALine() {
        List<String> lines = AvailabilityDaySummary.format(
                SAT, SAT, new AvailabilityEstimateResult(List.of(), List.of()));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("9/12(토) 남는 시간 없음");
    }

    @Test
    @DisplayName("가용 구간의 합계와 신뢰도를 함께 말한다")
    void reportsTotalAndConfidence() {
        AvailabilityEstimateResult result = new AvailabilityEstimateResult(
                List.of(
                        window(SAT, 10, 0, 12, 30, AvailabilityConfidence.HIGH),
                        window(SAT, 14, 0, 15, 0, AvailabilityConfidence.LOW)),
                List.of());

        String line = AvailabilityDaySummary.format(SAT, SAT, result).get(0);

        assertThat(line).contains("남는 시간 3시간 30분");
        assertThat(line).contains("10:00~12:30, 14:00~15:00");
        assertThat(line).as("하루 안에서 가장 약한 근거를 말한다").endsWith("(추정)");
    }

    @Test
    @DisplayName("가용 구간이 하나도 추정이 아니면 꼬리표를 붙이지 않는다")
    void noTagWhenEverythingIsConfirmed() {
        AvailabilityEstimateResult result = new AvailabilityEstimateResult(
                List.of(window(SAT, 9, 0, 10, 0, AvailabilityConfidence.HIGH)), List.of());

        assertThat(AvailabilityDaySummary.format(SAT, SAT, result).get(0))
                .doesNotContain("추정")
                .contains("남는 시간 1시간");
    }

    private static AvailabilityWindow window(LocalDate date, int fromHour, int fromMinute,
                                             int toHour, int toMinute, AvailabilityConfidence confidence) {
        return new AvailabilityWindow(date.atTime(fromHour, fromMinute), date.atTime(toHour, toMinute),
                AvailabilitySource.DEFAULT_INFERENCE, confidence, "테스트");
    }
}
