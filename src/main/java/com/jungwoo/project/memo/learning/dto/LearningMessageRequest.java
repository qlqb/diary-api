package com.jungwoo.project.memo.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LearningMessageRequest {

    @NotNull
    private Long topicId;

    @NotBlank
    private String message;
}
