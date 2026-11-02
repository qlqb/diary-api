package com.jungwoo.project.memo.ai.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_conversation_drafts 한 행. fields/missingRequired/promotedSuggestionIds는 JSON 문자열 그대로다 —
 * 해석은 {@link DraftCodec}이 한다.
 *
 * <p>version 컬럼이 없다. draft 갱신은 대화 잠금(ai_conversations.active_request_message_id) 안에서만
 * 일어나므로 행 단위 낙관적 락이 설 자리가 없다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversationDraft {

    private Long draftId;
    private Long userId;
    private Long conversationId;
    private Long draftGroupId;
    private DraftType draftType;
    private String label;
    private DraftStatus status;
    /** {"field": {"value":…, "source":…, "reason":…, "confirmationRequired":…}, …} */
    private String fields;
    /** ["field", …] */
    private String missingRequired;
    private int askCount;
    /** [suggestionId, …] 또는 null */
    private String promotedSuggestionIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
