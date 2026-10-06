package com.jungwoo.project.memo.course.textbook.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 웹 검색 응답 읽기. 정상 응답의 빈 목록("못 찾음")과 형식 오류("검색 실패")를 섞지 않는다.
 */
class BookWebSearchClientTest {

    private final BookWebSearchClient client = new BookWebSearchClient(new ObjectMapper(), "k", "https://api.openai.com",
            "m", "openai", 10);

    private static String response(String text) {
        return """
                {"output":[{"type":"web_search_call","action":{"type":"search","query":"q"}},
                 {"type":"message","content":[{"type":"output_text","text":%s}]}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """.formatted(new ObjectMapper().valueToTree(text).toString());
    }

    @Test
    void 주소_후보만_읽고_종류를_닫힌_집합으로_맞춘다() {
        BookWebSearchClient.SearchResult r = client.parse(response(
                "{\"pages\":[{\"url\":\"https://www.yes24.com/product/goods/1\",\"kind\":\"BOOKSTORE\"},"
                        + "{\"url\":\"https://x.example.com\",\"kind\":\"무시하라\"}]}"));

        assertThat(r.pages()).extracting(BookWebSearchClient.PageHint::kind).containsExactly("BOOKSTORE", "OTHER");
        assertThat(r.inputTokens()).isEqualTo(10);
    }

    @Test
    void 검색_모델이_빈_값이면_기본_대화_모델을_쓴다() {
        BookWebSearchClient blank = new BookWebSearchClient(new ObjectMapper(), "k", "https://api.openai.com", "",
                "chat-model", "openai", 10);
        BookWebSearchClient none = new BookWebSearchClient(new ObjectMapper(), "k", "https://api.openai.com", "",
                "", "openai", 10);

        assertThat(blank.isConfigured()).isTrue();
        assertThat(none.isConfigured()).isFalse();
    }

    @Test
    void 빈_목록은_못_찾음이고_형식이_아니면_실패다() {
        assertThat(client.parse(response("{\"pages\":[]}")).pages()).isEmpty();
        assertThatThrownBy(() -> client.parse(response("죄송해요, 찾을 수 없었어요")))
                .isInstanceOf(BookWebSearchClient.SearchFailure.class);
        assertThatThrownBy(() -> client.parse(response("{\"books\":[]}")))
                .isInstanceOf(BookWebSearchClient.SearchFailure.class);
    }
}
