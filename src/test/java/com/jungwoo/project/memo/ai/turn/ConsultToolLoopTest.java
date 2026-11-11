package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 도구 단계 실행기의 계약: 서버가 받은 순서대로 하나씩 실행하고, 상한(모델·도구)과 실패를 작업 공간에 미완료로 남긴다. 원본은 어느 경우에도
 * 건드리지 않는다(도구는 작업 공간만 바꾼다). 모델은 가짜로 대신한다 — 실제 호출은 실호출 검증(S0.5·S6)에서 본다.
 */
class ConsultToolLoopTest {

    /** 호출 횟수만 세는 가짜 도구. */
    static final class CountingTool implements ConsultTool {
        final AtomicInteger calls = new AtomicInteger();
        final String name;
        final RuntimeException fail;

        CountingTool(String name, RuntimeException fail) {
            this.name = name;
            this.fail = fail;
        }

        public String name() {
            return name;
        }

        public String description() {
            return "테스트 도구";
        }

        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }

        public String progressLabel() {
            return name + " 중";
        }

        public String call(TurnWorkspace ws, JsonNode args) {
            calls.incrementAndGet();
            if (fail != null) {
                throw fail;
            }
            return "{\"result\":\"OK\"}";
        }
    }

    private static ChatResponse toolCalls(String... names) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(new AssistantMessage.ToolCall("c" + i, "function", names[i], "{}"));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static ChatResponse done() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("확인 끝"))));
    }

    @SuppressWarnings("unchecked")
    private static ConsultToolLoop loop(ChatModel model, List<ConsultTool> tools) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new ConsultToolLoop(provider, tools, mock(ConsultTurnStepRecorder.class), mock(AiUsageLimitService.class));
    }

    private static TurnWorkspace ws() {
        return new TurnWorkspace(1L, 2L, 3L, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25).atTime(14, 0), List.of());
    }

    @Test
    void 도구를_받은_순서대로_실행하고_진행_표시를_보내고_모델이_멈추면_끝난다() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCalls("a", "b"), done());
        CountingTool a = new CountingTool("a", null);
        CountingTool b = new CountingTool("b", null);
        List<String> progress = new ArrayList<>();

        TurnWorkspace ws = loop(model, List.of(a, b)).run(ws(), "ctx", "msg", progress::add);

        assertThat(a.calls.get()).isEqualTo(1);
        assertThat(b.calls.get()).isEqualTo(1);
        assertThat(progress).containsExactly("a 중", "b 중");
        assertThat(ws.modelCalls()).isEqualTo(2);
        assertThat(ws.limitNotes()).isEmpty();
    }

    @Test
    void 모델_호출_상한에_닿으면_멈추고_미완료로_남긴다() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCalls("a"));
        CountingTool a = new CountingTool("a", null);

        TurnWorkspace ws = loop(model, List.of(a)).run(ws(), "ctx", "msg", null);

        assertThat(ws.modelCalls()).isEqualTo(5);
        assertThat(ws.limitNotes()).anyMatch(n -> n.contains("모델 호출 상한"));
        assertThat(ConsultToolLoop.answerBlock(ws)).contains("미완료").contains("확인한 것과 확인하지 못한 것");
    }

    @Test
    void 도구_호출_상한을_넘는_호출은_실행하지_않는다() {
        ChatModel model = mock(ChatModel.class);
        String[] many = new String[13];
        java.util.Arrays.fill(many, "a");
        when(model.call(any(Prompt.class))).thenReturn(toolCalls(many), done());
        CountingTool a = new CountingTool("a", null);

        TurnWorkspace ws = loop(model, List.of(a)).run(ws(), "ctx", "msg", null);

        assertThat(a.calls.get()).isEqualTo(12);
        assertThat(ws.limitNotes()).anyMatch(n -> n.contains("도구 호출 상한"));
    }

    @Test
    void 도구_실패와_잘못된_인자는_결과_코드로_돌려주고_계속한다() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCalls("broken", "bad", "missing"), done());
        CountingTool broken = new CountingTool("broken", new IllegalStateException("db down"));
        CountingTool bad = new CountingTool("bad", new IllegalArgumentException("from이 필요하다"));

        TurnWorkspace ws = loop(model, List.of(broken, bad)).run(ws(), "ctx", "msg", null);

        assertThat(broken.calls.get()).isEqualTo(1);
        assertThat(bad.calls.get()).isEqualTo(1);
        assertThat(ws.modelCalls()).isEqualTo(2); // 실패가 루프를 끊지 않는다
    }

    @Test
    void 모델_호출이_실패하면_그때까지로_끝내고_원인을_남긴다() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        TurnWorkspace ws = loop(model, List.of(new CountingTool("a", null))).run(ws(), "ctx", "msg", null);

        assertThat(ws.limitNotes()).anyMatch(n -> n.contains("모델 호출이 실패"));
        assertThat(ws.staged()).isEmpty();
    }
}
