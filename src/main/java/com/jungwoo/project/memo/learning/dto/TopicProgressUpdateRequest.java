package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TopicProgressUpdateRequest {

    @NotNull
    private TopicProgressStatus status;
}
