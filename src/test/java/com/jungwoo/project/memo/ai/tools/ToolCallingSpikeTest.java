package com.jungwoo.project.memo.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S0.5 실호출 선검증 — 현재 상담 모델·Spring AI 2.0.0으로 "모델은 도구 호출만 내고 서버가 직접 실행하는" 루프가 되는지 본다.
 *
 * <p>실제 모델을 부르므로 기본으로 돌지 않는다. {@code CONSULT_SPIKE=1}일 때만 실행한다. 도구 결과는 쿠팡 근무 시나리오의 고정
 * 값이다 — 여기서 확인하는 것은 API 계약(도구 정의 전달, 도구 호출 수신, 결과 전달, 순서)과 모델의 도구 선택이지 계산 정확성이 아니다.
 * 결과는 build/consult-spike/report.md에 남는다.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=openai"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "CONSULT_SPIKE", matches = "1")
class ToolCallingSpikeTest {

    private static final int MAX_MODEL_CALLS = 6;

    @Autowired
    private ChatModel chatModel;

    @Value("${ai.consultation.model}")
    private String model;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> staged = new ArrayList<>();

    @Test
    void shiftSkipThenAvailability() throws Exception {
        List<ToolCallback> tools = List.of(
                tool("find_schedule", "기간 안의 등록된 일정(반복 일정 회차·약속)을 찾는다. 결과의 ref로 변경을 제안한다.",
                        """
                        {"type":"object","properties":{"date":{"type":"string","description":"YYYY-MM-DD"},
                         "query":{"type":"string","description":"제목 검색어(선택)"}},"required":["date"],"additionalProperties":false}"""),
                tool("propose_change", "일정 변경을 제안 목록에 올린다. 원본은 바뀌지 않는다(사용자가 검토 후 적용).",
                        """
                        {"type":"object","properties":{"op":{"type":"string","enum":["SKIP_OCCURRENCE"]},
                         "ref":{"type":"string","description":"find_schedule이 준 ref"}},"required":["op","ref"],"additionalProperties":false}"""),
                tool("preview_availability", "그날 남는 시간을 계산한다. withProposals=true면 제안 목록을 반영한 가정 상태로 계산한다.",
                        """
                        {"type":"object","properties":{"date":{"type":"string"},"until":{"type":"string","description":"HH:mm, 공부 끝 시각(선택)"},
                         "withProposals":{"type":"boolean"}},"required":["date","withProposals"],"additionalProperties":false}"""));

        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("""
                        너는 일정·공부 계획 상담 도우미다. 지금은 2026-10-25(일) 14:00, Asia/Seoul이다.
                        필요한 정보는 도구로 확인한다. 변경은 propose_change로 제안만 하고, 원본은 사용자가 적용해야 바뀐다는 점을 답에 밝힌다.
                        최종 답은 한국어 3문장 이내."""),
                new UserMessage("오늘 쿠팡 근무 빼고 8시까지 공부 잡아줘")));

        StringBuilder report = new StringBuilder("# S0.5 tool-calling spike\n\nmodel: " + model + "\n\n");
        long turnStarted = System.currentTimeMillis();
        String finalText = null;
        int calls = 0;
        int toolCalls = 0;
        for (; calls < MAX_MODEL_CALLS; ) {
            calls++;
            long started = System.currentTimeMillis();
            ChatResponse response = chatModel.call(new Prompt(messages,
                    OpenAiChatOptions.builder().model(model).reasoningEffort("none").toolCallbacks(tools).build()));
            long latency = System.currentTimeMillis() - started;
            AssistantMessage out = response.getResult().getOutput();
            var usage = response.getMetadata().getUsage();
            report.append("## call ").append(calls).append(" — ").append(latency).append(" ms, tokens in/out ")
                    .append(usage == null ? "?" : usage.getPromptTokens() + "/" + usage.getCompletionTokens()).append('\n');
            if (!out.hasToolCalls()) {
                finalText = out.getText();
                report.append("final: ").append(finalText).append("\n\n");
                break;
            }
            messages.add(out);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : out.getToolCalls()) {
                toolCalls++;
                String result = execute(call.name(), json.readTree(call.arguments()));
                report.append("- tool ").append(call.name()).append(' ').append(call.arguments()).append(" → ").append(result).append('\n');
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
            report.append('\n');
        }
        report.append("total: ").append(System.currentTimeMillis() - turnStarted).append(" ms, model calls ").append(calls)
                .append(", tool calls ").append(toolCalls).append(", staged ").append(staged).append('\n');

        Path out = Path.of("build/consult-spike/report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

        assertThat(staged).as("오늘 회차 제외 제안").contains("SKIP_OCCURRENCE routine:220@2026-10-25");
        assertThat(finalText).as("최종 설명").isNotBlank();
    }

    private String execute(String name, JsonNode args) {
        return switch (name) {
            case "find_schedule" -> args.path("query").asText("").contains("이동")
                    ? "[]"
                    : """
                    [{"ref":"routine:220@2026-10-25","kind":"ROUTINE_OCCURRENCE","title":"쿠팡알바 근무","start":"2026-10-25T17:00","end":"2026-10-26T02:00"},
                     {"ref":"routine:218@2026-10-25","kind":"ROUTINE_OCCURRENCE","title":"쿠팡알바 이동시간","start":"2026-10-25T15:30","end":"2026-10-25T17:00",
                      "relation":"근무 앞 인접(제목·시각 추정, 저장된 연결 아님)"}]""";
            case "propose_change" -> {
                staged.add(args.path("op").asText() + " " + args.path("ref").asText());
                yield "{\"result\":\"STAGED\",\"effect\":\"그 회차가 바쁜 시간에서 빠진다(가정)\",\"items\":" + staged.size() + "}";
            }
            case "preview_availability" -> args.path("withProposals").asBoolean() && staged.stream().anyMatch(s -> s.contains("220"))
                    ? "{\"windows\":[\"14:00~20:00\"],\"freeMinutes\":360,\"busy\":[\"15:30~17:00 쿠팡알바 이동시간(유지됨)\"],"
                    + "\"note\":\"이동시간은 제안 목록에 없어 그대로 막혀 있다\",\"freeMinutesExcludingTravel\":270}"
                    : "{\"windows\":[\"14:00~15:30\"],\"freeMinutes\":90,\"busy\":[\"15:30~17:00 이동\",\"17:00~02:00 쿠팡알바 근무\"]}";
            default -> "{\"result\":\"UNKNOWN_TOOL\"}";
        };
    }

    private static ToolCallback tool(String name, String description, String schema) {
        ToolDefinition definition = new DefaultToolDefinition(name, description, schema);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                // 서버 루프가 직접 실행한다. 여기로 오면 프레임워크가 자동 실행을 시도했다는 뜻이다.
                throw new IllegalStateException("자동 도구 실행이 일어났다: " + name);
            }
        };
    }
}
