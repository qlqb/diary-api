package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 상담 턴의 도구 단계(공통 상담 실행부). 모델이 도구를 고르고 서버가 하나씩 실행해 결과를 돌려준다 — 상한(모델 호출·도구 호출·시간) 안에서.
 *
 * <p>모델에게 도구를 실행시키지 않는다: Spring AI 2.0의 ChatModel은 도구 호출을 돌려줄 뿐이고(실행은 ChatClient advisor 몫), 여기서
 * 받은 순서대로 하나씩 실행·기록·제한한다. 이 클래스는 도메인을 모른다 — 도구 이름으로만 고른다.
 *
 * <p>도구 단계에는 자료 원문을 싣지 않는다(자료의 문장이 도구를 움직이지 못하게). 텍스트를 스트리밍하지 않는다 — 진행 표시만 보낸다.
 * 실패·상한·시간 초과면 그때까지 확인한 것과 못 한 것을 작업 공간에 남기고 끝낸다. 원본은 어느 경우에도 바뀌지 않는다.
 */
@Slf4j
@Component
public class ConsultToolLoop {

    public static final String USAGE_FEATURE = "AI_CONSULTATION_TOOL";

    private final ObjectProvider<ChatModel> chatModel;
    private final List<ConsultTool> tools;
    private final ConsultTurnStepRecorder recorder;
    private final AiUsageLimitService usage;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${ai.consultation.model:}")
    private String model;

    /** 도구 단계의 모델 호출 상한. 답변 호출 1회를 더해 턴당 상한(기본 6)이 된다. */
    @Value("${ai.consult.tools.max-model-calls:5}")
    private int maxModelCalls = 5;

    @Value("${ai.consult.tools.max-tool-calls:12}")
    private int maxToolCalls = 12;

    @Value("${ai.consult.tools.max-seconds:60}")
    private int maxSeconds = 60;

    @Value("${ai.consult.tools.enabled:true}")
    private boolean enabled = true;

    public ConsultToolLoop(ObjectProvider<ChatModel> chatModel, List<ConsultTool> tools, ConsultTurnStepRecorder recorder,
                           AiUsageLimitService usage) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.recorder = recorder;
        this.usage = usage;
    }

    public int maxSeconds() {
        return maxSeconds;
    }

    public boolean available() {
        return enabled && chatModel.getIfAvailable() != null;
    }

    static final String SYSTEM = """
            너는 일정·공부 계획 상담의 도구 단계다. 사용자 요청을 처리하는 데 필요한 사실을 도구로 확인하고, 필요하면 변경을 제안한다.
            이 단계에서는 사용자에게 답하지 않는다. 필요한 도구를 다 썼으면 도구 호출 없이 한 줄로 "확인 끝"이라고만 쓴다.

            규칙:
            - 등록된 일정·할 일·과제는 추측하지 말고 find_* 도구로 확인한다. 여러 개가 맞으면 제안하지 말고 그대로 둔다(답변 단계에서 묻는다).
            - 변경은 propose_change로 제안만 한다. 원본은 사용자가 카드에서 적용해야 바뀐다. 사용자가 그 변경을 말로 요청했을 때만 제안한다 —
              "8시까지 공부 잡아줘"만으로 근무를 빼는 제안을 하지 않는다. 그때는 원본으로 계산하고 겹치는 원인을 확인만 한다.
            - 사용자가 공부 시간·시간대·분량을 말했으면 set_study_time으로 정리한다(부분마다 원문 구절 그대로).
            - 공부 계획이 걸린 요청이면 preview_availability로 원본과(변경을 제안했다면) 가정 상태를 계산해 실제로 몇 분이 들어가는지 확인한다.
            - 같은 도구를 같은 인자로 반복하지 않는다. 결과 코드가 NOT_FOUND·READ_FAILED·INVALID면 그 사실을 그대로 두고 지어내지 않는다.
            """;

    /**
     * @param context  도구 단계 입력(화면 상태·합의·최근 대화·현재 시각). 자료 원문은 넣지 않는다
     * @param progress 진행 표시(도구마다)
     */
    public TurnWorkspace run(TurnWorkspace ws, String context, String userMessage, Consumer<String> progress) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            ws.note("도구 단계를 쓸 수 없었다(모델 미설정)");
            return ws;
        }
        long deadline = System.nanoTime() + maxSeconds * 1_000_000_000L;
        Map<String, ConsultTool> byName = new LinkedHashMap<>();
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ConsultTool tool : tools) {
            byName.put(tool.name(), tool);
            callbacks.add(definitionOnly(tool));
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().reasoningEffort("none").toolCallbacks(callbacks)
                .maxCompletionTokens(1500);
        if (this.model != null && !this.model.isBlank()) {
            options = options.model(this.model);
        }
        OpenAiChatOptions built = (OpenAiChatOptions) options.build();
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(SYSTEM),
                new UserMessage(context + "\n\n[사용자 요청]\n" + userMessage)));
        int seq = 0;
        while (true) {
            if (ws.modelCalls() >= maxModelCalls) {
                ws.note("도구 단계 모델 호출 상한(" + maxModelCalls + "회)에 닿아 확인을 멈췄다");
                recorder.record(ws, ++seq, "LIMIT", "model_calls", null, "LIMIT", null, null, null, null);
                break;
            }
            if (System.nanoTime() > deadline) {
                ws.note("도구 단계 시간 상한(" + maxSeconds + "초)에 닿아 확인을 멈췄다");
                recorder.record(ws, ++seq, "LIMIT", "time", null, "LIMIT", null, null, null, null);
                break;
            }
            ws.countModelCall();
            long started = System.currentTimeMillis();
            ChatResponse response;
            try {
                response = model.call(new Prompt(messages, built));
            } catch (RuntimeException e) {
                ws.note("도구 단계 모델 호출이 실패했다(" + e.getClass().getSimpleName() + ") — 그 뒤는 확인하지 못했다");
                recorder.record(ws, ++seq, "ERROR", "model", null, "MODEL_FAILED", e.getClass().getSimpleName(),
                        System.currentTimeMillis() - started, null, null);
                usage.record(ws.userId(), ws.conversationId(), ws.requestMessageId(), this.model, null, null, null,
                        UsageResultStatus.FAILED, "AI_GENERATION_FAILED", USAGE_FEATURE, null, null, null);
                break;
            }
            long latency = System.currentTimeMillis() - started;
            var u = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            Integer in = u == null ? null : u.getPromptTokens();
            Integer out = u == null ? null : u.getCompletionTokens();
            usage.record(ws.userId(), ws.conversationId(), ws.requestMessageId(), this.model, in, null, out,
                    UsageResultStatus.SUCCESS, null, USAGE_FEATURE, null, null, null);
            AssistantMessage reply = response.getResult().getOutput();
            if (reply.hasToolCalls() && System.nanoTime() > deadline) {
                // 모델 호출이 길어져 시간 상한을 넘겼다 — 이 응답의 도구는 실행하지 않는다.
                ws.note("도구 단계 시간 상한(" + maxSeconds + "초)에 닿아 마지막 확인을 하지 못했다");
                recorder.record(ws, ++seq, "LIMIT", "time", null, "LIMIT", null, latency, in, out);
                break;
            }
            recorder.record(ws, ++seq, "MODEL", "tool_round", null, reply.hasToolCalls() ? "TOOL_CALLS" : "DONE",
                    reply.hasToolCalls() ? null : reply.getText(), latency, in, out);
            if (!reply.hasToolCalls()) {
                break;
            }
            messages.add(reply);
            List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
            for (AssistantMessage.ToolCall call : reply.getToolCalls()) {
                String result;
                String code;
                long toolStarted = System.currentTimeMillis();
                ConsultTool tool = byName.get(call.name());
                if (ws.toolCalls() >= maxToolCalls) {
                    result = "{\"result\":\"LIMIT_REACHED\",\"message\":\"도구 호출 상한에 닿았다 — 확인을 멈추고 지금까지의 결과로 답한다\"}";
                    code = "LIMIT";
                    if (ws.limitNotes().stream().noneMatch(n -> n.startsWith("도구 호출 상한"))) {
                        ws.note("도구 호출 상한(" + maxToolCalls + "회)에 닿아 일부 확인을 하지 못했다");
                    }
                } else if (tool == null) {
                    result = "{\"result\":\"UNKNOWN_TOOL\"}";
                    code = "UNKNOWN_TOOL";
                } else {
                    ws.countToolCall();
                    if (progress != null) {
                        progress.accept(tool.progressLabel());
                    }
                    try {
                        JsonNode args = call.arguments() == null || call.arguments().isBlank()
                                ? json.createObjectNode() : json.readTree(call.arguments());
                        result = tool.call(ws, args);
                        code = resultCode(result);
                    } catch (IllegalArgumentException e) {
                        result = "{\"result\":\"INVALID_ARGS\",\"message\":" + quote(e.getMessage()) + "}";
                        code = "INVALID_ARGS";
                    } catch (Exception e) {
                        log.warn("상담 도구 실행 실패: tool={}, {}", call.name(), e.getClass().getSimpleName());
                        result = "{\"result\":\"READ_FAILED\",\"message\":\"서버에서 처리하지 못했다 — 확인하지 못한 것으로 다룬다\"}";
                        code = "READ_FAILED";
                    }
                }
                recorder.record(ws, ++seq, "TOOL", call.name(), call.arguments(), code, result,
                        System.currentTimeMillis() - toolStarted, null, null);
                ws.detail(call.name(), result);
                if ("READ_FAILED".equals(code)) {
                    ws.note(call.name() + " 조회가 서버에서 실패해 그 부분은 확인하지 못했다");
                }
                results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(results).build());
        }
        log.info("상담 도구 단계: conversationId={}, 모델 {}회, 도구 {}회, 제안 {}건, 시간 조건={}, 상한={}", ws.conversationId(),
                ws.modelCalls(), ws.toolCalls(), ws.staged().size(), ws.condition() == null ? null : ws.condition().status(),
                ws.limitNotes());
        return ws;
    }

    /** 답변 단계 프롬프트에 붙일 확인 결과. 서버가 확인한 것만 적는다. */
    public static String answerBlock(TurnWorkspace ws) {
        StringBuilder sb = new StringBuilder("[도구 단계 결과 — 서버가 확인한 사실. 이것을 근거로 답한다]\n");
        if (!ws.checkedFacts().isEmpty()) {
            sb.append("확인한 것: ").append(String.join(" / ", ws.checkedFacts())).append('\n');
        }
        if (ws.staged().isEmpty()) {
            sb.append("제안한 변경: 없음\n");
        } else {
            sb.append("제안한 변경(적용 전 — 사용자가 아래 카드에서 고르고 적용해야 바뀐다):\n");
            for (TurnWorkspace.Staged s : ws.staged()) {
                sb.append("  ").append(s.no()).append(") ").append(s.op().summary())
                        .append(s.selected() ? " [선택됨]" : " [선택 안 됨 — 함께 뺄지 사용자에게 물어볼 것]");
                if (s.linkReason() != null) {
                    sb.append(" · ").append(s.linkReason());
                }
                sb.append('\n');
            }
        }
        if (ws.condition() != null) {
            sb.append("말한 공부 시간: ").append(ws.condition().label()).append(" (").append(ws.condition().status()).append(")");
            if (!ws.condition().notes().isEmpty()) {
                sb.append(" 메모: ").append(String.join(" / ", ws.condition().notes()));
            }
            if (!ws.condition().issues().isEmpty()) {
                sb.append(" 확인 못 함: ").append(String.join(" / ", ws.condition().issues().stream()
                        .map(StudyTimeIssueText::of).toList()));
            }
            sb.append('\n');
        }
        if (!ws.details().isEmpty()) {
            sb.append("도구 결과(원문 그대로, 근거로 쓴다):\n");
            ws.details().forEach(d -> sb.append("  ").append(d).append('\n'));
        }
        if (!ws.limitNotes().isEmpty()) {
            sb.append("미완료: ").append(String.join(" / ", ws.limitNotes()))
                    .append(" — 답에서 확인한 것과 확인하지 못한 것을 나눠 말한다\n");
        }
        sb.append("답변 규칙: 변경은 아직 적용되지 않았다. \"뺐어요\"가 아니라 \"빼는 안을 올렸어요, 확인 후 적용해 주세요\"처럼 말한다. "
                + "일정 후보(scheduleSuggestions)는 비운다 — 변경은 위 목록이 전부다.\n");
        return sb.toString();
    }

    private static final class StudyTimeIssueText {
        static String of(com.jungwoo.project.memo.ai.brief.StudyTimeCondition.Issue i) {
            return i.detail() + (i.quote() == null ? "" : "(\"" + i.quote() + "\")");
        }
    }

    private static String resultCode(String result) {
        int i = result.indexOf("\"result\":\"");
        if (i < 0) {
            return "OK";
        }
        int start = i + 10;
        int end = result.indexOf('"', start);
        return end > start ? result.substring(start, end) : "OK";
    }

    private String quote(String s) {
        try {
            return json.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static ToolCallback definitionOnly(ConsultTool tool) {
        ToolDefinition definition = new DefaultToolDefinition(tool.name(), tool.description(), tool.inputSchema());
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                // 실행은 이 루프가 한다. 여기로 오면 프레임워크가 자동 실행을 시도한 것이다.
                throw new IllegalStateException("자동 도구 실행은 쓰지 않는다: " + tool.name());
            }
        };
    }
}
