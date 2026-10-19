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

    /**
     * 이 항목의 근거(PlanItemEvidence)의 JSON. 서버만 쓴다.
     *
     * originalPayload와 나눠 두는 것이 요점이다 — 그쪽은 사용자가 고친 값이
     * editedPayload로 다시 쓰이는 자리이고, 근거는 사용자가 쓰는 값이 아니다. 컬럼이
     * 다르면 "클라이언트가 서버 소유 값을 덮어쓸 수 있는가"를 검사 코드가 아니라 구조가
     * 답한다.
     */
    private String evidenceJson;

    private Long targetItemId;

    private Long baseVersion;

    private AiProposalItemStatus status;

    private AiProposalItemType createdItemType;

    private Long createdItemId;

    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;
}
