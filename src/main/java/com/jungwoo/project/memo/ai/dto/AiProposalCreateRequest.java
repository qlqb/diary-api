package com.jungwoo.project.memo.ai.dto;

import jakarta.validation.constraints.NotBlank;
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
public class AiProposalCreateRequest {

    @NotBlank(message = "상담 원문은 필수입니다")
    private String sourceText;

    @NotNull(message = "targetDate는 필수입니다")
    private LocalDate targetDate;
}
