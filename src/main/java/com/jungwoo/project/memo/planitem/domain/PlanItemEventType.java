package com.jungwoo.project.memo.planitem.domain;

/**
 * 계획 항목 조정 이벤트 타입.
 *
 * event는 "상태 전이 또는 발생한 사건"을 표현한다.
 * 주간 회고는 이 이벤트를 집계해서 만든다.
 * (자주 밀린 것 = MOVED 집계, 작게 줄인 것 = REDUCED 집계, 보류한 것 = HOLD 집계)
 */
public enum PlanItemEventType {
    CREATED,    // 생성 (확장용, 1차-A에서는 미사용)
    DONE,       // 완료 처리
    MOVED,      // 다른 날짜로 이동 (from_date → to_date)
    REDUCED,    // 작게 줄임 (before_title → after_title)
    HOLD,       // 보류
    REOPENED,   // 완료취소
    RESUMED,    // 보류 해제 (확장용, 1차-A API에는 없음)
    DELETED     // 삭제 (soft delete와 함께 기록, 확장용)
}
