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
}
