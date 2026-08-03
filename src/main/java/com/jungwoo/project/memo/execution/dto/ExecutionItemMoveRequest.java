package com.jungwoo.project.memo.execution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemMoveRequest {

    @NotNull(message = "이동할 날짜는 필수입니다")
    private LocalDate toDate;

    @NotNull(message = "version은 필수입니다")
    private Long version;

    private String reason;
}
