package com.jungwoo.project.memo.assignment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.change.ToolArgs;
import com.jungwoo.project.memo.ai.turn.ConsultTool;
import com.jungwoo.project.memo.ai.turn.TurnWorkspace;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.dto.AssignmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 열려 있는 과제 조회(마감·출처). 마감 정정·완료는 assignmentId로 제안한다. */
@Component
@RequiredArgsConstructor
public class FindAssignmentsTool implements ConsultTool {

    private final CourseAssignmentService assignmentService;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "find_assignments";
    }

    @Override
    public String description() {
        return "아직 끝나지 않은 과제를 찾는다(마감과 그 출처: USER 사용자 확정 / SOURCE 자료 명시 / ESTIMATED 추정). query는 제목 일부(선택).";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"query":{"type":"string"}},"additionalProperties":false}""";
    }

    @Override
    public String progressLabel() {
        return "과제 확인 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        String query = ToolArgs.text(args, "query");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AssignmentResponse a : assignmentService.listOpen(ws.userId())) {
            if (query != null && (a.getTitle() == null || !a.getTitle().contains(query))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assignmentId", a.getAssignmentId());
            row.put("title", a.getTitle());
            row.put("dueKind", String.valueOf(a.getDueKind()));
            row.put("dueDate", a.getDueDate() == null ? null : a.getDueDate().toString());
            row.put("dueAt", a.getDueAt() == null ? null : a.getDueAt().toString());
            row.put("dueSource", String.valueOf(a.getDueSource()));
            rows.add(row);
            if (rows.size() >= 40) {
                break;
            }
        }
        ws.checked("과제" + (query == null ? "" : " '" + query + "'") + " " + rows.size() + "건");
        try {
            return json.writeValueAsString(Map.of("result", rows.isEmpty() ? "NOT_FOUND" : "OK", "items", rows));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
