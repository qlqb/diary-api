package com.jungwoo.project.memo.execution.domain;

/**
 * 실행 조각 조정 사건. execution_item_events.event_type 체크 제약과 1:1 대응한다.
 *
 * 실제 수행 결과(완료/일부 진행/진행 없음)는 ExecutionRecord가 담당한다.
 */
public enum ExecutionEventType {

    /** 생성. */
    CREATED,

    /** 다른 날짜로 이동. */
    MOVED,

    /** 분량·범위를 줄임. */
    REDUCED,

    /** 하나를 여러 조각으로 나눔. */
    SPLIT,

    /** 보류. */
    HOLD,

    /** 보류 해제. */
    RESUMED,

    /** 완료 취소 등으로 계획 상태로 재개함. */
    REOPENED,

    /** 계획에서 제외. */
    CANCELLED,

    /** 중요도 변경. */
    PRIORITY_CHANGED,

    /** 삭제. soft delete와 함께 기록. */
    DELETED
}
