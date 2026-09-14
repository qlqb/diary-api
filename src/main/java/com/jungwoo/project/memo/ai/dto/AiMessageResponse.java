package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** GET .../messages 이력 조회, 새로고침 후 대화 복원용. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessageResponse {

    private Long messageId;
    private MessageRole role;
    private String content;
    private AiResponseType responseType;
    private Long proposalId;
    private LocalDateTime createdAt;
}
