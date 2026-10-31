package com.jungwoo.project.memo.plan.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 계획 강도. 사용자가 고르는 것은 이 셋 중 하나이고, 서버가 <b>추정 가용시간</b>에 곱해 학습
 * 예산(target_minutes)을 만들어 AI에 넘긴다.
 *
 * 왜 개수가 아니라 시간인가(11-period-plan.md §5-1-1):
 * 개수는 사용자가 판단할 수 있는 단위가 아니고("이번 달 계획 몇 개?"에는 근거가 없다),
 * 모델이 한 항목을 셋으로 쪼개면 그대로 게이밍된다. 시간은 쪼개도 합이 같다.
 *
 * 왜 고정 시간이 아니라 비율인가:
 * 예전 값(가볍게 240분/주, 보통 600분/주, 집중 1,080분/주)은 사용자의 실제 시간표를 보지 않은
 * 고정 기준선이었다. 수업·알바가 많은 주에도 같은 숫자를 내밀었고, 구간 경계(2일→3일,
 * 14일→15일)에서 예산이 역전하는 알려진 문제가 있었다. 지금은 "이 기간에 실제로 남는 시간
 * 중 얼마를 계획으로 채울지"다. 남는 시간은 AvailabilityEstimateService가 고정 일정·지난
 * 시간·사용자 예외를 빼고 추정한다.
 *
 * ★ 비율 세 값은 이 파일 한 곳에만 있다. 호출부가 퍼센트를 스스로 만들지 않는다. 100%
 * 모드는 만들지 않는다 — 집중도 15%는 휴식과 변동의 여유다.
 *
 * ★ 과거 PlanVersion.target_minutes는 그때 저장된 값이다. 다시 계산하지 않는다. 새 정책은
 * 새 초안에만 적용된다.
 */
@Getter
@RequiredArgsConstructor
public enum PlanIntensity {

    /** 핵심만 배치하고 큰 여유를 남긴다. */
    LIGHT(40),
    /** 주요 과목을 고르게 배치한다. */
    NORMAL(65),
    /** 대부분을 배치하되 휴식·변동 여유를 남긴다. */
    FOCUSED(85);

    /** 학습 예산은 이 단위로 내림한다 — 배치 격자(15분)와 맞춘다. */
    public static final int TARGET_ROUNDING_MINUTES = 15;

    /** 강도가 지정되지 않았고 직전 계획도 없을 때 쓰는 값. */
    public static final PlanIntensity DEFAULT = NORMAL;

    /** 추정 가용시간 중 학습에 배정하는 비율(%). */
    private final int fillPercent;

    /**
     * 이 강도로 주어진 가용시간을 계획할 때의 학습 예산(분).
     *
     * <p>가용시간 × 비율을 15분 단위로 내린다. 예: 600분이면 LIGHT 240, NORMAL 390, FOCUSED
     * 510. 가용시간이 0이면 0이고, 그때 호출부는 항목을 억지로 만들지 않는다.
     *
     * @param estimatedAvailableMinutes 계획 기간의 AvailabilityWindow 분 합계(0 이상)
     */
    public int targetMinutesFor(int estimatedAvailableMinutes) {
        if (estimatedAvailableMinutes < 0) {
            throw new IllegalArgumentException("가용시간은 0 이상이어야 한다: " + estimatedAvailableMinutes);
        }
        int raw = Math.toIntExact(Math.floorDiv((long) estimatedAvailableMinutes * fillPercent, 100L));
        return raw - raw % TARGET_ROUNDING_MINUTES;
    }

    /** 화면에 "남는 시간의 65%"처럼 보여줄 값. */
    public double fillRatio() {
        return fillPercent / 100.0;
    }
}
