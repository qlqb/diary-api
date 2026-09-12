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
    private LocalDateTime recordedAt;
}
