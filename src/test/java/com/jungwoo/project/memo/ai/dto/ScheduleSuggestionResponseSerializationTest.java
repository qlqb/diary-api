package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면으로 나가는 JSON을 실제 직렬화 경로로 검증한다.
 *
 * <p><b>왜 이 테스트가 따로 필요한가.</b> 기존 단위 테스트는 서비스가 돌려준 객체를 직접
 * 비교한다 — {@code response.getPayload()}에 값이 들어 있는지만 본다. 그건 직렬화 경로를
 * 한 번도 지나지 않으므로, 객체는 멀쩡한데 JSON은 비어 나가는 상태를 못 잡는다.
 *
 * <p>실제로 그 일이 있었다. payload가 Jackson 2의 {@code com.fasterxml.jackson.databind.JsonNode}
 * 였는데, 이 프로젝트의 HTTP·SSE 직렬화는 Jackson 3(tools.jackson)이 한다
 * (JacksonConfig 주석 참고 — Spring Boot 4 기본값이라 Jackson 2 ObjectMapper 빈은 응답
 * 변환용으로 자동 구성되지 않는다). Jackson 3은 Jackson 2 JsonNode를 트리로 인식하지 못해
 * 내부 구조를 내보내거나 빈 객체를 썼고, 검토 카드에 제목·시각·장소가 하나도 뜨지 않았다.
 * 단위 테스트는 전부 통과하고 있었다.
 *
 * <p>그래서 여기서는 <b>HTTP가 쓰는 매퍼(Jackson 3)</b>로 직접 직렬화해 문자열을 본다.
 */
class ScheduleSuggestionResponseSerializationTest {

    /** HTTP·SSE가 실제로 쓰는 매퍼. 코드 안에서 쓰는 Jackson 2 ObjectMapper가 아니다. */
    private final JsonMapper httpMapper = JsonMapper.builder().build();

    private AiScheduleSuggestion suggestion() {
        return AiScheduleSuggestion.builder()
                .suggestionId(700L)
                .userId(1L)
                .conversationId(10L)
                .sourceMessageId(100L)
                .kind(ScheduleSuggestionKind.COMMITMENT)
                .status(ScheduleSuggestionStatus.PROPOSED)
                .build();
    }

    private Map<String, Object> commitmentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "친구 약속");
        payload.put("startAt", "2026-09-04T19:00");
        payload.put("endAt", "2026-09-04T21:00");
        payload.put("locationText", "홍대");
        return payload;
    }

    @Test
    void payloadFields_surviveTheHttpSerializer() {
        String json = httpMapper.writeValueAsString(
                ScheduleSuggestionResponse.of(suggestion(), commitmentPayload()));

        // 카드가 그리는 값이 전부 살아 있어야 한다. 하나라도 빠지면 빈 카드가 뜬다.
        assertThat(json).contains("\"title\":\"친구 약속\"");
        assertThat(json).contains("\"startAt\":\"2026-09-04T19:00\"");
        assertThat(json).contains("\"endAt\":\"2026-09-04T21:00\"");
        assertThat(json).contains("\"locationText\":\"홍대\"");
        assertThat(json).contains("\"suggestionId\":700");
        assertThat(json).contains("\"kind\":\"COMMITMENT\"");
    }

    @Test
    void payload_isNotSerializedAsAnOpaqueObject() {
        String json = httpMapper.writeValueAsString(
                ScheduleSuggestionResponse.of(suggestion(), commitmentPayload()));

        // Jackson 2 JsonNode를 Jackson 3이 POJO로 볼 때 새어 나오던 내부 필드들.
        assertThat(json).doesNotContain("_children");
        assertThat(json).doesNotContain("_nodeFactory");
        assertThat(json).doesNotContain("\"payload\":{}");
    }

    @Test
    void editedPayload_comesBackFromTheHttpDeserializer() {
        // 사용자가 카드에서 고친 값이 서버까지 오는 경로. 여기서 끊기면 수정이 조용히 무시된다.
        String body = """
                {"editedPayload":{"title":"친구 약속","startAt":"2026-09-04T19:00",
                 "endAt":"2026-09-04T21:30","locationText":null}}""";

        ScheduleSuggestionApplyRequest request =
                httpMapper.readValue(body, ScheduleSuggestionApplyRequest.class);

        assertThat(request.getEditedPayload())
                .containsEntry("title", "친구 약속")
                .containsEntry("endAt", "2026-09-04T21:30");
    }

    @Test
    void routinePayload_keepsItsOwnShape() {
        // kind에 따라 모양이 다르다 — 반복 일정 쪽 필드도 그대로 나가야 한다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "알바");
        payload.put("daysOfWeek", java.util.List.of("THURSDAY"));
        payload.put("startTime", "18:00");
        payload.put("endTime", "23:00");
        payload.put("effectiveFrom", "2026-09-01");

        AiScheduleSuggestion routine = AiScheduleSuggestion.builder()
                .suggestionId(701L).userId(1L).conversationId(10L).sourceMessageId(100L)
                .kind(ScheduleSuggestionKind.ROUTINE)
                .status(ScheduleSuggestionStatus.PROPOSED)
                .build();

        String json = httpMapper.writeValueAsString(ScheduleSuggestionResponse.of(routine, payload));

        assertThat(json).contains("\"daysOfWeek\":[\"THURSDAY\"]");
        assertThat(json).contains("\"effectiveFrom\":\"2026-09-01\"");
    }

    /**
     * 왜 JsonNode를 쓰면 안 되는지를 코드로 못박는다.
     *
     * <p>이 테스트가 깨지는 날은 Jackson 3이 Jackson 2 트리를 이해하게 된 날이다. 그전까지는
     * 경계 DTO에 com.fasterxml.jackson.databind.JsonNode를 두면 안 된다.
     */
    @Test
    void jackson3_cannotSerializeJackson2JsonNode_asATree() {
        com.fasterxml.jackson.databind.JsonNode jackson2Tree =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(commitmentPayload());

        String json;
        try {
            json = httpMapper.writeValueAsString(jackson2Tree);
        } catch (RuntimeException e) {
            // 아예 실패하는 것도 "트리로 못 읽는다"의 한 형태다.
            return;
        }
        // 실패하지 않는다면 최소한 원래 필드가 그대로 나오지는 않는다.
        assertThat(json).doesNotContain("\"title\":\"친구 약속\"");
    }
}
