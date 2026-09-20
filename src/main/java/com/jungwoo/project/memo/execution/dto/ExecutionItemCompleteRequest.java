package com.jungwoo.project.memo.execution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 완료 요청. execution_records에 outcome=COMPLETED, completion_percent=100 레코드를
 * 생성하고 execution_items.status를 DONE으로 바꾸는 것을 한 트랜잭션으로 처리한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemCompleteRequest {

    @NotNull(message = "version은 필수입니다")
    private Long version;

    private Integer actualMinutes;

    private String note;
    /**
     * 다 하지 못했거나 어려웠던 이유(선택): TIME(시간이 없었다) / CONCEPT(개념에서 막혔다) / ENERGY(컨디션) / OTHER.
     * 묻지 않았거나 답하지 않았으면 null이다 — "시간이 없었다"와 "개념에서 막혔다"는 다음 계획을 다르게 바꾼다.
     */
    private String blockerKind;
}
