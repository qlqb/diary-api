package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상담 대화 하나. ai_conversations 테이블과 1:1 대응하는 MyBatis 엔티티.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversation {

    private Long conversationId;

    private Long userId;

    private AiProposalTargetScope scope;

    private ConversationStatus status;

    /** 오래된 메시지 요약. 이번 버전은 컬럼만 두고 요약 생성 로직은 만들지 않는다. */
    private String summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
