package com.jungwoo.project.memo.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiStreamParserTest {

    @Test
    void splitsReplyAndJson_whenDelimiterArrivesInOneChunk() {
        AiStreamParser parser = new AiStreamParser();

        String emitted = parser.onChunk("안녕! 오늘 뭐부터 해볼까?\n<<<AI_STRUCTURED>>>\n{\"responseType\":\"CHAT\"}");

        assertThat(emitted).isEqualTo("안녕! 오늘 뭐부터 해볼까?\n");

        AiStreamParser.Result result = parser.finish();
        assertThat(result.reply()).isEqualTo("안녕! 오늘 뭐부터 해볼까?");
        assertThat(result.structuredJson()).isEqualTo("{\"responseType\":\"CHAT\"}");
    }

    @Test
    void neverEmitsStructuredJson_whenDelimiterSplitsAcrossChunks() {
        AiStreamParser parser = new AiStreamParser();
        StringBuilder emitted = new StringBuilder();

        // 구분자를 여러 토큰으로 쪼개서 흘려보낸다 (실제 스트리밍처럼 임의 경계에서 잘림).
        String[] chunks = {"좋아, 정리해볼까", "<<<AI_", "STRUCTURED>>>", "\n{\"responseType\":\"OFFER\"}"};
        for (String chunk : chunks) {
            emitted.append(parser.onChunk(chunk));
        }

        assertThat(emitted.toString()).doesNotContain("responseType").doesNotContain("<<<AI_STRUCTURED>>>");
        assertThat(emitted.toString()).startsWith("좋아, 정리해볼까");

        AiStreamParser.Result result = parser.finish();
        assertThat(result.reply()).isEqualTo("좋아, 정리해볼까");
        assertThat(result.structuredJson()).isEqualTo("{\"responseType\":\"OFFER\"}");
    }

    @Test
    void fallsBackToPlainReply_whenDelimiterNeverAppears() {
        AiStreamParser parser = new AiStreamParser();

        String emitted = parser.onChunk("그냥 텍스트만 옴");

        AiStreamParser.Result result = parser.finish();
        assertThat(result.structuredJson()).isNull();
        assertThat(emitted + result.unemittedTail()).isEqualTo("그냥 텍스트만 옴");
    }

    @Test
    void onChunk_returnsEmptyString_afterDelimiterFound() {
        AiStreamParser parser = new AiStreamParser();
        parser.onChunk("답변\n<<<AI_STRUCTURED>>>\n");

        String afterDelimiter = parser.onChunk("{\"responseType\":\"CHAT\"}");

        assertThat(afterDelimiter).isEmpty();
    }
}
