package com.jungwoo.project.memo.scheduling.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.change.ToolArgs;
import com.jungwoo.project.memo.ai.turn.ConsultTool;
import com.jungwoo.project.memo.ai.turn.TurnWorkspace;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 등록된 일정(반복 일정 회차·약속) 조회. 회차는 routineId와 원래 날짜(date)로 가리킨다. 원본만 보여 준다(제안은 반영하지 않는다). */
@Component
@RequiredArgsConstructor
public class FindScheduleTool implements ConsultTool {

    static final int MAX_DAYS = 31;
    static final int MAX_ROWS = 60;

    private final RoutineOccurrenceService occurrenceService;
    private final CommitmentService commitmentService;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "find_schedule";
    }

    @Override
    public String description() {
        return "기간 안의 등록된 일정(반복 일정 회차·약속)을 원본 그대로 찾는다. query는 제목 일부(선택). 결과의 routineId+date, commitmentId로 "
                + "변경을 제안한다. 근무·수업·약속이 언제인지 추측하지 말고 이것으로 확인한다.";
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
        return "일정 확인 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        LocalDate from = ToolArgs.date(args, "from");
        LocalDate to = ToolArgs.date(args, "to");
        if (to.isBefore(from) || to.isAfter(from.plusDays(MAX_DAYS))) {
            throw new IllegalArgumentException("기간은 시작 이후 " + MAX_DAYS + "일 안이어야 한다");
        }
        String query = ToolArgs.text(args, "query");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RoutineOccurrence o : occurrenceService.expand(ws.userId(), from, to)) {
            if (query != null && (o.title() == null || !o.title().contains(query))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "ROUTINE_OCCURRENCE");
            row.put("routineId", o.routineId());
            row.put("date", o.sourceDate().toString());
            row.put("title", o.title());
            row.put("start", o.startAt().toString());
            row.put("end", o.endAt().toString());
            if (o.moved()) {
                row.put("moved", true);
            }
            rows.add(row);
        }
        for (Commitment c : commitmentService.findOverlapping(ws.userId(), from, to)) {
            if (query != null && (c.getTitle() == null || !c.getTitle().contains(query))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "COMMITMENT");
            row.put("commitmentId", c.getCommitmentId());
            row.put("title", c.getTitle());
            row.put("start", c.getStartAt().toString());
            row.put("end", c.getEndAt().toString());
            if (c.getDerivedFromCommitmentId() != null) {
                row.put("derivedFromCommitmentId", c.getDerivedFromCommitmentId());
            }
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("start")).compareTo(String.valueOf(b.get("start"))));
        boolean truncated = rows.size() > MAX_ROWS;
        ws.checked("일정 " + from + "~" + to + (query == null ? "" : " '" + query + "'") + " " + rows.size() + "건");
        try {
            return json.writeValueAsString(Map.of("result", rows.isEmpty() ? "NOT_FOUND" : "OK",
                    "items", truncated ? rows.subList(0, MAX_ROWS) : rows, "truncated", truncated));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
