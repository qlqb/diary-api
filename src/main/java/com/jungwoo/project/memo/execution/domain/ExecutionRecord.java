package com.jungwoo.project.memo.execution.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 실제로 일어난 결과. execution_records 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 완료 처리는 이 레코드 생성과 execution_items.status='DONE' 갱신을
 * 하나의 트랜잭션으로 함께 저장한다. 완료 Record 없이 DONE 상태만 남기지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionRecord {

    private Long executionRecordId;

    private Long userId;

    private Long executionItemId;

    private ExecutionRecordOutcome outcome;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private Integer actualMinutes;

    /** COMPLETED=100, PARTIAL=1~99, NOT_DONE=0. */
    private Integer completionPercent;

    private String note;
    /**
     * 다 하지 못했거나 어려웠던 이유(선택): TIME(시간이 없었다) / CONCEPT(개념에서 막혔다) / ENERGY(컨디션) / OTHER.
     * 묻지 않았거나 답하지 않았으면 null이다 — "시간이 없었다"와 "개념에서 막혔다"는 다음 계획을 다르게 바꾼다.
     */
    private String blockerKind;

    /** outcome이 PARTIAL일 때만 값이 있다. 남은 분량을 담은 새 조각. */
    private Long remainingExecutionItemId;

    private LocalDateTime recordedAt;

    private LocalDateTime createdAt;
}
