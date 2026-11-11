package com.jungwoo.project.memo.execution.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.change.ToolArgs;
import com.jungwoo.project.memo.ai.turn.ConsultTool;
import com.jungwoo.project.memo.ai.turn.TurnWorkspace;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 할 일(실행 조각) 조회 — 상태(할 일·완료·보류)와 version 없이 id로 가리킨다. 변경 제안은 executionItemId로 한다. */
@Component
@RequiredArgsConstructor
public class FindTasksTool implements ConsultTool {

    static final int MAX_ROWS = 60;

    private final ExecutionItemMapper itemMapper;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "find_tasks";
    }

    @Override
    public String description() {
        return "기간 안의 할 일(실행 조각)을 찾는다. 상태(PLANNED 할 일, DONE 완료, HOLD 보류)와 시각을 준다. query는 제목 일부(선택). "
                + "완료·보류·옮기기 같은 변경은 결과의 executionItemId로 제안한다.";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"from":{"type":"string","description":"YYYY-MM-DD"},
                 "to":{"type":"string","description":"YYYY-MM-DD(포함)"},"query":{"type":"string"}},
                 "required":["from","to"],"additionalProperties":false}""";
    }

    @Override
    public String progressLabel() {
        return "할 일 확인 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        LocalDate from = ToolArgs.date(args, "from");
        LocalDate to = ToolArgs.date(args, "to");
        if (to.isBefore(from) || to.isAfter(from.plusDays(31))) {
            throw new IllegalArgumentException("기간은 시작 이후 31일 안이어야 한다");
        }
        String query = ToolArgs.text(args, "query");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExecutionItem i : itemMapper.findByUserIdAndPlanningRange(ws.userId(), from, to)) {
            if (query != null && (i.getTitle() == null || !i.getTitle().contains(query))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("executionItemId", i.getExecutionItemId());
            row.put("title", i.getTitle());
            row.put("status", String.valueOf(i.getStatus()));
            row.put("placement", String.valueOf(i.getPlacementType()));
            row.put("date", i.getScheduledDate() == null ? null : i.getScheduledDate().toString());
            if (i.getScheduledStartAt() != null) {
                row.put("start", i.getScheduledStartAt().toString());
                row.put("end", i.getScheduledEndAt().toString());
            }
            row.put("expectedMinutes", i.getExpectedMinutes());
            rows.add(row);
        }
        boolean truncated = rows.size() > MAX_ROWS;
        ws.checked("할 일 " + from + "~" + to + (query == null ? "" : " '" + query + "'") + " " + rows.size() + "건");
        try {
            return json.writeValueAsString(Map.of("result", rows.isEmpty() ? "NOT_FOUND" : "OK",
                    "items", truncated ? rows.subList(0, MAX_ROWS) : rows, "truncated", truncated));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
