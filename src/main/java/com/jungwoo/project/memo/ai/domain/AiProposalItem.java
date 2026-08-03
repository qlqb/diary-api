package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 변경안 개별 항목. ai_proposal_items 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * originalPayload/editedPayload는 JSON 문자열이다 (DB CHECK json_valid).
 * 이번 범위에서 targetItemId/baseVersion은 항상 null이다 — 기존 항목 수정 제안은
 * 후속 범위다. 이번 범위는 항상 새 ExecutionItem을 만드는 제안만 다룬다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalItem {

    private Long proposalItemId;

    private Long proposalId;

    private Long userId;

    private AiProposalItemType itemType;

    private String originalPayload;

    private String editedPayload;

    private Long targetItemId;

    private Long baseVersion;

    private AiProposalItemStatus status;

    private AiProposalItemType createdItemType;

    private Long createdItemId;

    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;
}
