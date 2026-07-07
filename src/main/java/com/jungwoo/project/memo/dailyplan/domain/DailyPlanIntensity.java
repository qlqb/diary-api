package com.jungwoo.project.memo.dailyplan.domain;

/**
 * 오늘을 얼마나 강하게 운영할지.
 * 구 RECOVERY 모드는 intensity=LIGHT + conditionTags 조합으로 흡수되었다.
 */
public enum DailyPlanIntensity {
    LIGHT,
    NORMAL,
    FOCUSED
}
