package com.jungwoo.project.memo.execution.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 작게 줄이기 요청. reducedTitle, expectedMinutes 중 최소 하나는 있어야 하고
 * 기존 값과 실제로 달라야 한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemReduceRequest {

    private String reducedTitle;

    @Positive(message = "예상 소요 시간은 양수여야 합니다")
    private Integer expectedMinutes;

    private String reason;

    @NotNull(message = "version은 필수입니다")
    private Long version;
}
