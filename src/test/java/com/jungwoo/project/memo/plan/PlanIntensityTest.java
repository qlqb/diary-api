package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프리셋 값은 실사용 근거가 없는 추정이고 나중에 고칠 예정이다. 그래서 이 테스트는
 * "값이 맞다"를 증명하는 것이 아니라 **현재 값을 고정**한다 — 누가 숫자를 만지면 어떤
 * 기간의 예산이 얼마나 움직이는지 테스트 실패로 드러나게 하는 것이 목적이다.
 *
 * 특히 구간 경계의 역전(11-period-plan.md §8-1)을 명시적으로 박아둔다. 알려진 문제를
 * 테스트가 침묵하면 나중에 "원래 이랬나?"가 된다.
 */
class PlanIntensityTest {

    // ===== 구간별 해석 =====

    @ParameterizedTest(name = "{0} × {1}일 = {2}분")
    @CsvSource({
            // 1~2일: 일 단위
            "LIGHT,    1,   90",
            "NORMAL,   1,  180",
            "FOCUSED,  1,  240",
            "LIGHT,    2,  180",
            "NORMAL,   2,  360",
            "FOCUSED,  2,  480",
            // 3~14일: 주 단위
            "LIGHT,    3,  103",
            "NORMAL,   3,  257",
            "FOCUSED,  3,  463",
            "LIGHT,    7,  240",
            "NORMAL,   7,  600",
            "FOCUSED,  7, 1080",
            "LIGHT,   14,  480",
            "NORMAL,  14, 1200",
            "FOCUSED, 14, 2160",
            // 15~31일: 지속 가능선
            "LIGHT,   15,  429",
            "NORMAL,  15, 1029",
            "FOCUSED, 15, 1800",
            "LIGHT,   30,  857",
            "NORMAL,  30, 2057",
            "FOCUSED, 30, 3600",
            "LIGHT,   31,  886",
            "NORMAL,  31, 2126",
            "FOCUSED, 31, 3720",
    })
    void baselineMinutes_matchesPresetTable(PlanIntensity intensity, int days, int expected) {
        assertThat(intensity.baselineMinutes(days)).isEqualTo(expected);
    }

    @Test
    void baselineMinutes_documentedExamplesHold() {
        // 명세 §5-1-1이 본문에 적어둔 네 값. 문서와 코드가 갈라지면 여기서 걸린다.
        assertThat(PlanIntensity.FOCUSED.baselineMinutes(1)).isEqualTo(240);
        assertThat(PlanIntensity.FOCUSED.baselineMinutes(7)).isEqualTo(1080);
        assertThat(PlanIntensity.FOCUSED.baselineMinutes(30)).isEqualTo(3600);
        assertThat(PlanIntensity.NORMAL.baselineMinutes(7)).isEqualTo(600);
    }

    @Test
    void baselineMinutes_thirtyDaysIsFarBelowLinearProjection() {
        // 선형이면 1080 × 30/7 = 4629분(77시간). 지속 가능선을 쓰는 이유가 이것이다.
        int linear = Math.round(1080f * 30 / 7);
        assertThat(linear).isEqualTo(4629);
        assertThat(PlanIntensity.FOCUSED.baselineMinutes(30)).isEqualTo(3600).isLessThan(linear);
    }

    // ===== 구간 경계: 알려진 역전 (§8-1) =====

    @Test
    void knownIssue_budgetDropsWhenCrossingTwoToThreeDays() {
        // 하루를 늘렸는데 예산이 줄어든다. LIGHT가 가장 심하다(-42.8%).
        // 개정안은 14↔15일만 지적했으나 이쪽이 더 크다.
        assertThat(PlanIntensity.LIGHT.baselineMinutes(2)).isEqualTo(180);
        assertThat(PlanIntensity.LIGHT.baselineMinutes(3)).isEqualTo(103);

        for (PlanIntensity intensity : PlanIntensity.values()) {
            assertThat(intensity.baselineMinutes(3))
                    .as("%s: 2일 → 3일에서 예산이 줄어드는 것이 현재 동작이다", intensity)
                    .isLessThan(intensity.baselineMinutes(2));
        }
    }

    @Test
    void knownIssue_budgetDropsWhenCrossingFourteenToFifteenDays() {
        assertThat(PlanIntensity.NORMAL.baselineMinutes(14)).isEqualTo(1200);
        assertThat(PlanIntensity.NORMAL.baselineMinutes(15)).isEqualTo(1029);

        for (PlanIntensity intensity : PlanIntensity.values()) {
            assertThat(intensity.baselineMinutes(15))
                    .as("%s: 14일 → 15일에서 예산이 줄어드는 것이 현재 동작이다", intensity)
                    .isLessThan(intensity.baselineMinutes(14));
        }
    }

    @Test
    void withinEachBand_budgetGrowsMonotonically() {
        // 경계를 제외하면 기간이 늘수록 예산도 늘어야 한다. 구간 안에서까지 튀면
        // 위 두 역전은 "경계 문제"가 아니라 계산식 자체의 문제가 된다.
        for (PlanIntensity intensity : PlanIntensity.values()) {
            assertBandIncreasing(intensity, 1, 2);
            assertBandIncreasing(intensity, 3, 14);
            assertBandIncreasing(intensity, 15, 31);
        }
    }

    private void assertBandIncreasing(PlanIntensity intensity, int from, int to) {
        for (int d = from; d < to; d++) {
            assertThat(intensity.baselineMinutes(d + 1))
                    .as("%s: %d일 → %d일", intensity, d, d + 1)
                    .isGreaterThan(intensity.baselineMinutes(d));
        }
    }

    // ===== 강도 간 순서 =====

    @Test
    void intensitiesAreOrderedAtEveryDuration() {
        // 어떤 기간에서도 가볍게 < 보통 < 집중이어야 한다. 구간별로 다른 기준을 쓰므로
        // 이 순서가 뒤집힐 여지가 있다.
        for (int days = 1; days <= 31; days++) {
            int light = PlanIntensity.LIGHT.baselineMinutes(days);
            int normal = PlanIntensity.NORMAL.baselineMinutes(days);
            int focused = PlanIntensity.FOCUSED.baselineMinutes(days);
            assertThat(light).as("%d일: LIGHT < NORMAL", days).isLessThan(normal);
            assertThat(normal).as("%d일: NORMAL < FOCUSED", days).isLessThan(focused);
        }
    }

    // ===== 경계·기본값 =====

    @Test
    void baselineMinutes_rejectsNonPositiveDays() {
        assertThatThrownBy(() -> PlanIntensity.NORMAL.baselineMinutes(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanIntensity.NORMAL.baselineMinutes(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultIsNormal() {
        assertThat(PlanIntensity.DEFAULT).isEqualTo(PlanIntensity.NORMAL);
    }

    @Test
    void weeklyMinutesForDisplay_matchesTheSelectorLabels() {
        // 강도 선택 화면의 "주 4시간쯤 / 10시간쯤 / 18시간쯤".
        assertThat(PlanIntensity.LIGHT.weeklyMinutesForDisplay()).isEqualTo(240);
        assertThat(PlanIntensity.NORMAL.weeklyMinutesForDisplay()).isEqualTo(600);
        assertThat(PlanIntensity.FOCUSED.weeklyMinutesForDisplay()).isEqualTo(1080);
    }

    @Test
    void enumNamesMatchTheDatabaseCheckConstraint() {
        // chk_ai_proposals_plan_intensity / chk_plan_versions_intensity가
        // ('LIGHT','NORMAL','FOCUSED')를 강제한다. enum 상수 이름을 바꾸면 저장이 실패한다.
        assertThat(PlanIntensity.values())
                .extracting(Enum::name)
                .containsExactly("LIGHT", "NORMAL", "FOCUSED");
    }
}
