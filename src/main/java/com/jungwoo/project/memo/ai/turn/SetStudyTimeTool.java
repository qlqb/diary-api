package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.ai.brief.StudyTimeInterpreter;
import com.jungwoo.project.memo.ai.change.ToolArgs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 말한 공부 시간 조건을 정리한다. 서버가 부분마다 사용자 원문(이 대화의 사용자 발언)에서 값을 확인한다 — 확인하지 못한 부분은
 * 버리지 않고 issues로 돌려준다. 자료 원문의 문장은 근거가 될 수 없다(사용자 발언만 대조한다).
 */
@Component
public class SetStudyTimeTool implements ConsultTool {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "set_study_time";
    }

    @Override
    public String description() {
        return "사용자가 말한 공부 시간 조건을 정리한다. windows=공부할 시간대, exclusions=그 안에서 뺄 시간(식사·휴식), amount=따로 말한 총 공부량, "
                + "intent=FILL(그 시간에 공부를 잡아 달라)/AVAILABLE(비어 있다는 정보). 부분마다 quote에 사용자 원문 구절을 그대로 적는다. "
                + "오전·오후를 말하지 않았으면 공부 시간으로 자연스러운 쪽으로 적는다. 서버가 원문과 대조해 status·notes·issues를 준다.";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                 "windows":{"type":"array","items":{"type":"object","properties":{"from":{"type":"string"},"until":{"type":"string"},
                   "date":{"type":"string"},"quote":{"type":"string"}},"required":["quote"]}},
                 "exclusions":{"type":"array","items":{"type":"object","properties":{"from":{"type":"string"},"until":{"type":"string"},
                   "label":{"type":"string"},"quote":{"type":"string"}},"required":["from","quote"]}},
                 "amount":{"type":"object","properties":{"minutes":{"type":"integer"},"per":{"type":"string","enum":["DAY","PLAN"]},
                   "quote":{"type":"string"}}},
                 "intent":{"type":"string","enum":["FILL","AVAILABLE"]},
                 "planDays":{"type":"integer","description":"계획 일수(오늘만이면 1)"}},
                 "additionalProperties":false}""";
    }

    @Override
    public String progressLabel() {
        return "말한 시간 확인 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        List<StudyTimeInterpreter.Part> parts = new ArrayList<>();
        args.path("windows").forEach(w -> parts.add(new StudyTimeInterpreter.Part("WINDOW", ToolArgs.text(w, "quote"),
                ToolArgs.text(w, "from"), ToolArgs.text(w, "until"), ToolArgs.text(w, "date"), null, null, null)));
        args.path("exclusions").forEach(x -> parts.add(new StudyTimeInterpreter.Part("EXCLUSION", ToolArgs.text(x, "quote"),
                ToolArgs.text(x, "from"), ToolArgs.text(x, "until"), ToolArgs.text(x, "date"), null, null,
                ToolArgs.text(x, "label"))));
        JsonNode amount = args.path("amount");
        if (amount.hasNonNull("minutes")) {
            parts.add(new StudyTimeInterpreter.Part("AMOUNT", ToolArgs.text(amount, "quote"), null, null, null,
                    amount.get("minutes").asInt(), ToolArgs.text(amount, "per"), null));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("windows·exclusions·amount 중 하나는 있어야 한다");
        }
        int days = args.path("planDays").canConvertToInt() ? Math.max(1, args.path("planDays").asInt()) : 1;
        StudyTimeCondition c = StudyTimeInterpreter.interpret(parts, ToolArgs.text(args, "intent"), ws.utterances(),
                ws.today(), days);
        ws.condition(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", c.status());
        out.put("label", c.label());
        out.put("notes", c.notes());
        out.put("issues", c.issues().stream().map(i -> i.code() + ": " + i.detail()).toList());
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
