package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 변경안 헤더. ai_proposals 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 모델은 변경안을 만들 뿐 DB를 직접 바꾸지 않는다.
 * 사용자가 검토·수정해 적용한 것만 execution_items에 반영된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposal {

    private Long proposalId;

    private Long userId;

    private Long conversationId;

    /**
     * 이 제안을 만든 ASSISTANT 메시지(사용자 메시지가 아니다). 한 메시지는 제안을 하나만
     * 만들 수 있다(uq_ai_proposals_source_message). 그 ASSISTANT가 어떤 사용자 요청에
     * 대한 응답인지는 ai_messages.reply_to_message_id로 이미 추적되므로, 여기서 사용자
     * 메시지를 직접 가리키면 같은 관계를 두 컬럼이 중복 보관하게 된다.
     */
    private Long sourceMessageId;

    private AiProposalTargetScope targetScope;

    private AiProposalStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime respondedAt;
}
