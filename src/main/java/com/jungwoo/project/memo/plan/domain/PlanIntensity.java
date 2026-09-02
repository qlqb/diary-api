package com.jungwoo.project.memo.plan.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 계획 강도. 사용자가 고르는 것은 이 셋 중 하나이고, 서버가 기간과 곱해 시간 예산
 * (target_minutes)을 만들어 AI에 넘긴다.
 *
 * 왜 개수가 아니라 시간인가(11-period-plan.md §5-1-1):
 * 개수는 사용자가 판단할 수 있는 단위가 아니고("이번 달 계획 몇 개?"에는 근거가 없다),
 * 모델이 한 항목을 셋으로 쪼개면 그대로 게이밍된다. 시간은 쪼개도 합이 같다.
 *
 * ★ 아래 숫자는 실사용 근거가 없는 추정이다. 값을 고칠 때 이 파일만 고치면 되도록
 * 구간 경계(DAILY_MAX_DAYS / WEEKLY_MAX_DAYS)까지 여기에 모아 둔다. 계산식은
 * baselineMinutes() 하나뿐이고 호출부는 분 단위를 스스로 만들지 않는다.
 *
 * 왜 주당 시간을 기간에 그대로 비례시키지 않는가:
 * FOCUSED를 주 18시간으로 두고 30일에 선형 적용하면 77시간이 된다. 4주 내내 최고 강도를
 * 유지한다는 뜻이라 지켜지지 않는다. 반대로 하루짜리는 2.6시간이라 집중이라기엔 약하다.
 * 그래서 1~2일은 일 단위로, 3일 이상은 주 단위로, 15일 이상은 더 낮은 지속 가능선으로
 * 해석한다.
 *
 * ★ 알려진 문제: 구간 경계에서 예산이 역전한다(하루 늘렸는데 예산이 줄어든다).
 * LIGHT 기준 2일 180분 → 3일 103분(-42.8%), 14일 480분 → 15일 429분(-10.6%).
 * 실사용 전에 근거 없이 숫자를 다시 만지지 않기로 하고 그대로 두었다 — 판단과 선택지는
 * 11-period-plan.md §8-1에 있다. PlanIntensityTest가 현재 값을 고정하므로, 값을 바꾸면
 * 테스트가 먼저 깨져 변경이 눈에 보인다.
 */
@Getter
@RequiredArgsConstructor
public enum PlanIntensity {

    LIGHT(90, 240, 200),
    NORMAL(180, 600, 480),
    FOCUSED(240, 1080, 840);

    /** 이 일수까지는 일 단위로 해석한다. */
    private static final int DAILY_MAX_DAYS = 2;

    /** 이 일수까지는 주 단위로 해석하고, 넘으면 지속 가능선을 쓴다. */
    private static final int WEEKLY_MAX_DAYS = 14;

    private static final int DAYS_PER_WEEK = 7;

    /** 강도가 지정되지 않았고 직전 계획도 없을 때 쓰는 값. */
    public static final PlanIntensity DEFAULT = NORMAL;

    /** 1~2일 계획에서 하루당 분. */
    private final int dailyMinutes;

    /** 3~14일 계획에서 주당 분. */
    private final int weeklyMinutes;

    /** 15일 이상 계획에서 주당 분. 4주 내내 유지할 수 있는 선. */
    private final int longTermWeeklyMinutes;

    /**
     * 이 강도로 주어진 일수를 계획할 때의 <b>기준</b> 학습 시간(분).
     *
     * 최종 목표가 아니다. 이 값은 AI에게 기준선으로 제시되고, AI가 사용자 상황(instruction,
     * 고정 일정, 직전 회고)을 보고 조정한 값이 실제 targetMinutes가 된다. 조정된 값만
     * ai_proposals.plan_target_minutes에 저장한다 — 프리셋은 출발점일 뿐이다.
     *
     * @param days 계획 기간의 일수(양끝 포함). 1 이상이어야 한다.
     */
    public int baselineMinutes(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("계획 일수는 1 이상이어야 한다: " + days);
        }
        if (days <= DAILY_MAX_DAYS) {
            return dailyMinutes * days;
        }
        int weekly = days <= WEEKLY_MAX_DAYS ? weeklyMinutes : longTermWeeklyMinutes;
        return Math.toIntExact(Math.round((double) weekly * days / DAYS_PER_WEEK));
    }

    /** 강도 선택 화면에 "주 10시간쯤"으로 보여줄 값. 구간과 무관한 대표값이다. */
    public int weeklyMinutesForDisplay() {
        return weeklyMinutes;
    }
}
