package com.jungwoo.project.memo.ai.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 본문은 Jackson 3(HTTP 변환기)로 읽힌다. 예전 화면은 answer.lookup을 보내지 않고, evidenceLookup은 서버가 정하는 값이라
 * 아무도 보내지 않는다 — 그래도 읽혀야 한다(2026-10-03: 원시형 boolean으로 두었다가 모든 상담 요청이 500이 됐다. 단위 테스트는
 * 본문 변환을 거치지 않아 잡지 못했고 실제 서버 검증에서 드러났다).
 */
class AiMessageRequestJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 새_칸이_없는_예전_본문도_읽힌다() {
        AiMessageRequest request = mapper.readValue("{\"message\":\"안녕\",\"requestedAction\":\"AUTO\",\"idempotencyKey\":\"k\","
                + "\"answer\":{\"questionId\":\"q-1\",\"choiceIds\":[\"c1\"],\"skipped\":false}}", AiMessageRequest.class);

        assertThat(request.getMessage()).isEqualTo("안녕");
        assertThat(request.getAnswer().lookupRequested()).isFalse();
        assertThat(request.evidenceLookupRequested()).isFalse();
    }

    @Test
    void 자료_찾기_답을_읽는다() {
        AiMessageRequest request = mapper.readValue("{\"message\":\"찾아줘\",\"requestedAction\":\"AUTO\",\"idempotencyKey\":\"k\","
                + "\"answer\":{\"questionId\":\"q-1\",\"choiceIds\":[],\"skipped\":false,\"lookup\":true}}", AiMessageRequest.class);

        assertThat(request.getAnswer().lookupRequested()).isTrue();
    }
}
