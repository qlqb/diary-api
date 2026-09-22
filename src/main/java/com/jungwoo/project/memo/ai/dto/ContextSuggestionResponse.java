package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.ContextChangeOperation;
import com.jungwoo.project.memo.ai.domain.ContextSuggestionStatus;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 화면에 보여줄 Context 변경 후보 카드. targetContextContent/targetContextStatus는
 * SUPERSEDE/MARK_STALE/ARCHIVE/CONFIRM에서 "지금 값 -> 제안 값"을 보여주기 위해 조회 시점의
 * 대상 Context 내용을 함께 담는다(ADD는 대상이 없으므로 항상 null).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContextSuggestionResponse {

    private Long suggestionId;
    private Long conversationId;
    private Long sourceMessageId;
    private ContextChangeOperation operation;
    private Long targetContextId;
    private String targetContextContent;
    private UserContextStatus targetContextStatus;
    private String proposedContent;
    private String reason;
    private ContextSuggestionStatus status;
    private Long resultingContextId;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
