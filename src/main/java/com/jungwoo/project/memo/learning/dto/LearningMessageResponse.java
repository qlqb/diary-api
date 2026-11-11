package com.jungwoo.project.memo.learning.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LearningMessageResponse {
    private Long conversationId;
    private Long messageId;
    private String reply;
}
