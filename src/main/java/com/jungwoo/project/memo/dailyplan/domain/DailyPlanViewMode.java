package com.jungwoo.project.memo.dailyplan.domain;

/**
 * 오늘 하루의 보기 방식. (v2.1 확정: 2종만, 가설로 취급)
 */
public enum DailyPlanViewMode {
    TIME_TABLE,   // 정확한 시간표가 중요한 날
    CHECKLIST     // 오늘 안에 끝내는 것이 중요한 날
}
