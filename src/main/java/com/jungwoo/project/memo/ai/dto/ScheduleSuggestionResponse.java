package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 일정 후보 응답. 화면이 검토 카드를 그리는 데 필요한 것만 담는다.
 *
 * <p>payload를 문자열이 아니라 객체로 내보낸다 — 화면이 다시 파싱하게 하면 서버와
 * 화면이 각자 JSON을 다루는 두 벌 코드가 된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleSuggestionResponse {

    private Long suggestionId;
    private Long conversationId;
    private Long sourceMessageId;
    private ScheduleSuggestionKind kind;
    private Map<String, Object> payload;
    private ScheduleSuggestionStatus status;

    public static ScheduleSuggestionResponse of(AiScheduleSuggestion suggestion, Map<String, Object> payload) {
        return ScheduleSuggestionResponse.builder()
                .suggestionId(suggestion.getSuggestionId())
                .conversationId(suggestion.getConversationId())
                .sourceMessageId(suggestion.getSourceMessageId())
                .kind(suggestion.getKind())
                .payload(payload)
                .status(suggestion.getStatus())
                .build();
    }
}
