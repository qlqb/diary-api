package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import java.util.List;
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

    /**
     * 서버가 규칙대로 채운 값의 안내. {@code [{"field": "effectiveUntil", "reason": "ACTIVE_SEMESTER_END"}, ...]}.
     * draft에서 만든 후보에만 있고 그 외에는 빈 배열이다. payload 안이 아니라 밖에 둔다 — 카드가 [적용]
     * 때 되돌려 보내는 payload는 저장 요청 DTO 그대로여야 한다.
     */
    @Builder.Default
    private List<Map<String, Object>> fieldNotes = List.of();

    /** 확인 없이 넣은 추측. {@code [{"field": "anchor", "reason": "UNCONFIRMED"}]}. 없으면 빈 배열. */
    @Builder.Default
    private List<Map<String, Object>> assumedFields = List.of();

    public static ScheduleSuggestionResponse of(AiScheduleSuggestion suggestion, Map<String, Object> payload) {
        return of(suggestion, payload, List.of(), List.of());
    }

    public static ScheduleSuggestionResponse of(AiScheduleSuggestion suggestion, Map<String, Object> payload,
                                                List<Map<String, Object>> fieldNotes,
                                                List<Map<String, Object>> assumedFields) {
        return ScheduleSuggestionResponse.builder()
                .suggestionId(suggestion.getSuggestionId())
                .conversationId(suggestion.getConversationId())
                .sourceMessageId(suggestion.getSourceMessageId())
                .kind(suggestion.getKind())
                .payload(payload)
                .status(suggestion.getStatus())
                .fieldNotes(fieldNotes != null ? fieldNotes : List.of())
                .assumedFields(assumedFields != null ? assumedFields : List.of())
                .build();
    }
}
