package com.jungwoo.project.memo.ai.consult;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 상담 턴 하나의 화면용 부가 정보. SSE {@code message.completed}와 대화 기록 조회가 같은 모양을 쓴다
 * (ai_messages.consult_json에 저장해 새로고침·재접속 뒤에도 같은 카드가 복구된다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsultView(Question question, List<Understanding> understanding, Direction direction, Activity activity) {

    public boolean isEmpty() {
        return question == null && direction == null && activity == null
                && (understanding == null || understanding.isEmpty());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String id, String text, String why, String topic, List<Choice> choices, boolean multiSelect) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(String id, String label) {
    }

    /**
     * @param id           MEMORY면 user_contexts.context_id, BRIEF면 합의 항목 id(문자열)
     * @param source       MEMORY(장기적으로 기억) / BRIEF(이번 계획의 합의)
     * @param evidenceType STATED / SELF_REPORT / OBSERVED / INFERRED
     * @param scopeLabel   "자료구조 · 9/19~9/20"처럼 사람이 읽는 적용 범위. 범위를 모르면 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Understanding(String id, String source, String text, String evidenceType, String scopeLabel,
                                boolean isNew) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Direction(String before, String after, String reason, boolean affectsDraft) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Activity(String kind, Long courseId, String title, List<ActivityItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityItem(String key, String label, Long topicId, Long sectionId) {
    }
}
