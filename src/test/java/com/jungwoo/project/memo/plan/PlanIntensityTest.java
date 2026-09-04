package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 강도는 고정 시간이 아니라 "추정 남는 시간 중 얼마를 계획으로 채울지"다. 예전 고정 기준선
 * (가볍게 240분/주 …)은 시간표를 보지 않았고 구간 경계(2→3일, 14→15일)에서 예산이 역전했다.
 * 비율 정책에는 구간이 없으므로 그 역전은 존재하지 않는다 — 이 테스트가 그 사실을 고정한다.
 *
 * 비율 값 자체(40/65/85)는 초기값이고 실사용 후 조정한다. 값을 바꾸면 여기서 먼저 깨진다.
 */
class PlanIntensityTest {

    @ParameterizedTest(name = "가용 {1}분 × {0} = {2}분")
    @CsvSource({
            // 명세 §11.3의 세 값
            "LIGHT,    600, 240",
            "NORMAL,   600, 390",
            "FOCUSED,  600, 510",
            // 15분 단위 내림: 925 × 65% = 601.25 → 600, 100 × 40% = 40 → 30
            "NORMAL,   925, 600",
            "LIGHT,    100,  30",
            // 0이면 0 — 호출부가 항목을 만들지 않는다
            "FOCUSED,    0,   0",
            // 하루짜리 기본 시간대(19~22시 = 180분)
            "LIGHT,    180,  60",
            "NORMAL,   180, 105",
            "FOCUSED,  180, 150",
    })
    void targetMinutesFor_isAvailabilityTimesRatio_flooredToFifteen(PlanIntensity intensity, int available, int expected) {
        assertThat(intensity.targetMinutesFor(available)).isEqualTo(expected);
    }

    @Test
    void ratiosLiveInOnePlace_andNoneIsOneHundredPercent() {
        assertThat(PlanIntensity.LIGHT.getFillPercent()).isEqualTo(40);
        assertThat(PlanIntensity.NORMAL.getFillPercent()).isEqualTo(65);
        assertThat(PlanIntensity.FOCUSED.getFillPercent()).isEqualTo(85);
        for (PlanIntensity intensity : PlanIntensity.values()) {
            assertThat(intensity.getFillPercent()).as("%s는 100%% 모드가 아니다", intensity).isLessThan(100);
        }
    }

    @Test
    void targetGrowsWithAvailability_andNeverExceedsIt() {
        // 고정 기준선의 구간 경계 역전은 비율 정책에 없다 — 가용시간이 늘면 예산도 줄지 않는다.
        for (PlanIntensity intensity : PlanIntensity.values()) {
            int previous = 0;
            for (int available = 0; available <= 3000; available += 15) {
                int target = intensity.targetMinutesFor(available);
                assertThat(target).as("%s @%d", intensity, available)
                        .isGreaterThanOrEqualTo(previous)
                        .isLessThanOrEqualTo(available);
                assertThat(target % PlanIntensity.TARGET_ROUNDING_MINUTES).isZero();
                previous = target;
            }
        }
    }

    @Test
    void intensitiesAreOrderedAtEveryAvailability() {
        for (int available = 60; available <= 3000; available += 60) {
            int light = PlanIntensity.LIGHT.targetMinutesFor(available);
            int normal = PlanIntensity.NORMAL.targetMinutesFor(available);
            int focused = PlanIntensity.FOCUSED.targetMinutesFor(available);
            assertThat(light).as("%d분: LIGHT <= NORMAL", available).isLessThanOrEqualTo(normal);
            assertThat(normal).as("%d분: NORMAL <= FOCUSED", available).isLessThanOrEqualTo(focused);
        }
    }

    @Test
    void targetMinutesFor_rejectsNegativeAvailability() {
        assertThatThrownBy(() -> PlanIntensity.NORMAL.targetMinutesFor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultIsNormal() {
        assertThat(PlanIntensity.DEFAULT).isEqualTo(PlanIntensity.NORMAL);
    }

    @Test
    void enumNamesMatchTheDatabaseCheckConstraint() {
        // chk_ai_proposals_plan_intensity / chk_plan_versions_intensity가
        // ('LIGHT','NORMAL','FOCUSED')를 강제한다. enum 상수 이름을 바꾸면 저장이 실패한다.
        // 과거 PlanVersion의 intensity·target_minutes는 그때 값 그대로 읽힌다.
        assertThat(PlanIntensity.values())
                .extracting(Enum::name)
                .containsExactly("LIGHT", "NORMAL", "FOCUSED");
    }
}
