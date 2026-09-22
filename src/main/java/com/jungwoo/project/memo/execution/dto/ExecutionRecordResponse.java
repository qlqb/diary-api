package com.jungwoo.project.memo.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기록 화면이 보는 "실제로 일어난 일" 한 줄.
 *
 * title/scheduledDate/courseId는 execution_records에 없는 값이라 실행 조각에서 조인해 채운다.
 * 계획 밖 결과(executionItemId가 null)면 그 값들도 null이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionRecordResponse {

    private Long executionRecordId;
    private Long executionItemId;
    private String title;
    private LocalDate scheduledDate;
    private Long courseId;
    private String outcome;
    private Integer actualMinutes;
    private Integer completionPercent;
    private String note;
    /**
     * 다 하지 못했거나 어려웠던 이유(선택): TIME(시간이 없었다) / CONCEPT(개념에서 막혔다) / ENERGY(컨디션) / OTHER.
     * 묻지 않았거나 답하지 않았으면 null이다 — "시간이 없었다"와 "개념에서 막혔다"는 다음 계획을 다르게 바꾼다.
     */
    private String blockerKind;
    private LocalDateTime recordedAt;
}
