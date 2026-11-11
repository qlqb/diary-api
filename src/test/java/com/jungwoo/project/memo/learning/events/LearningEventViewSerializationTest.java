package com.jungwoo.project.memo.learning.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 조회 응답의 payload가 HTTP 매퍼(Jackson 3)로 그대로 나간다(ScheduleSuggestionResponseSerializationTest와 같은 이유). */
class LearningEventViewSerializationTest {

    @Test
    void payload_필드가_HTTP_직렬화에서_살아_있다() {
        LearningEventLog.EventView view = new LearningEventLog.EventView(1L, "TEST", 2L, "ME", "COVERED_IN_CLASS", "COURSE",
                "-", null, null, null, Map.of("v", 1, "sources", List.of(Map.of("kind", "SECTION", "ref", "s:5"))),
                "INPUT", null, null, null, null, null);

        String json = JsonMapper.builder().build().writeValueAsString(view);

        assertThat(json).contains("\"payload\":{").contains("\"v\":1").contains("\"ref\":\"s:5\"");
    }
}
