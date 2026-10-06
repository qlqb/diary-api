package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI가 만든 장기 컨텍스트 변경 후보 한 건. ai_context_change_suggestions 테이블과 1:1
 * 대응하는 MyBatis 엔티티.
 *
 * 실제 장기 컨텍스트(UserContext)와 절대 같은 테이블에 두지 않는다. 이 행이 생겼다고
 * user_contexts가 바뀌지 않는다 — 사용자가 apply했을 때만 ContextChangeSuggestionService가
 * user_contexts를 바꾸고 resultingContextId를 채운다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiContextChangeSuggestion {

    private Long suggestionId;

    private Long userId;

    private Long conversationId;

    /** 이 후보를 만든 ASSISTANT 메시지(ai_proposals.source_message_id와 같은 관례). */
    private Long sourceMessageId;

    private ContextChangeOperation operation;

    /** ADD면 null, 그 외 연산은 필수. */
    private Long targetContextId;

    /** ADD/SUPERSEDE만 값을 가진다. */
    private String proposedContent;

    private String reason;

    private ContextSuggestionStatus status;

    /** apply가 만들거나 바꾼 user_contexts.context_id. 재적용 idempotency 판단에 쓴다. */
    private Long resultingContextId;

    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;
}
