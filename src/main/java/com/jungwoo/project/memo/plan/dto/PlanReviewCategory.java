package com.jungwoo.project.memo.plan.dto;

/**
 * 회고의 주 분류. 항목 하나는 정확히 하나를 갖는다.
 *
 * 라벨은 화면이 붙인다 — 실패 프레이밍을 피하는 문구 규칙이 화면 쪽 책임이기 때문이다.
 * 여기에는 판정 결과만 담는다.
 */
public enum PlanReviewCategory {

    /** 계획했고 했다. */
    DONE,

    /** 계획했고 일부 진행했다(원본이 DONE이고 최신 기록이 PARTIAL). */
    PARTIAL_DONE,

    /** 계획했고 날짜도 있는데 아직 안 했다. */
    REMAINING,

    /** 계획했는데 날짜를 아직 정하지 않았다. 이 설계의 존재 이유인 분류다. */
    UNPLACED,

    /** 잠시 멈춰뒀다. */
    HOLD,

    /** 계획에서 뺐다(취소 또는 삭제). */
    EXCLUDED,

    /** 계획에 없었는데 그 기간에 한 일. */
    OUTSIDE_PLAN,

    /** 부분 완료로 갈라져 나온 남은 분량. */
    LEFTOVER
}
