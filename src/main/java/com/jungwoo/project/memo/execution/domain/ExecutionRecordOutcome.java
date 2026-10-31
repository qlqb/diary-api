package com.jungwoo.project.memo.execution.domain;

/**
 * 실제로 일어난 결과. execution_records.outcome 체크 제약과 1:1 대응한다.
 *
 * completion_percent와의 관계(chk_execution_records_completion):
 * COMPLETED -> 100, PARTIAL -> 1~99, NOT_DONE -> 0
 */
public enum ExecutionRecordOutcome {
    COMPLETED,
    PARTIAL,
    NOT_DONE
}
