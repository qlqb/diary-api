package com.jungwoo.project.memo.execution.domain;

/**
 * 실행 조각의 현재 상태. execution_items.status 체크 제약과 1:1 대응한다.
 *
 * MOVED/REDUCED/HOLD 진입/재개 같은 조정은 상태가 아니라 ExecutionItemEvent로 기록한다.
 */
public enum ExecutionStatus {

    /** 계획됨. 기본값. */
    PLANNED,

    /** 일부만 진행. execution_records에 PARTIAL 기록이 남아있는 상태. */
    PARTIAL,

    /** 보류. "지금은 안 한다"는 판단이 이미 내려진 상태. */
    HOLD,

    /** 완료. 실제 결과는 ExecutionRecord에 따로 남는다. */
    DONE,

    /** 계획에서 제외. 필요가 사라졌거나 의도적으로 취소함. */
    CANCELLED
}
