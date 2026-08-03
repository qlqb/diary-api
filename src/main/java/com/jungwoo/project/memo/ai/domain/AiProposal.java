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

    private String conversationId;

    private AiProposalTargetScope targetScope;

    private AiProposalStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime respondedAt;
}
