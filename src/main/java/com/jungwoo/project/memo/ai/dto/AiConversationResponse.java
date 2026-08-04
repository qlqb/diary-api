package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversationResponse {

    private Long conversationId;
    private AiProposalTargetScope scope;
    private ConversationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
