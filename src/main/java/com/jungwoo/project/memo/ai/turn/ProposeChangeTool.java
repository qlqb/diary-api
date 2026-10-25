package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.change.ChangeBundleService;
import com.jungwoo.project.memo.ai.change.ChangeCheck;
import com.jungwoo.project.memo.ai.change.ChangeModule;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.ai.change.ChangeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 변경 제안 — 턴 작업 공간에 올린다. 원본은 바뀌지 않는다(사용자가 카드에서 적용해야 바뀐다). op 목록과 인자 설명은 등록된 도메인 모듈에서
 * 모은다 — 이 클래스는 도메인을 모른다.
 */
@Component
@RequiredArgsConstructor
public class ProposeChangeTool implements ConsultTool {

    private final ChangeRegistry registry;
    private final ChangeBundleService bundles;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "propose_change";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("기존 데이터에 대한 변경을 제안 목록에 올린다. 원본은 바뀌지 않는다 — 사용자가 검토 카드에서 적용해야 "
                + "바뀐다. 서버가 대상을 확인해 결과 코드(OK/NOT_FOUND/INVALID/ALREADY_DONE/CONFLICT/READ_FAILED/INVALID_ARGS)를 준다. "
                + "같은 대상에는 하나만. 함께 고를 만한 관련 변경(linked)이 나오면 사용자에게 함께 물어볼 것인지 정한다. op와 args:\n");
        Map<String, String> all = new TreeMap<>();
        registry.modules().forEach(m -> all.putAll(m.toolArgs()));
        all.forEach((op, desc) -> sb.append("- ").append(op).append(": ").append(desc).append('\n'));
        return sb.toString();
    }

    @Override
    public String inputSchema() {
        List<String> ops = new ArrayList<>();
        registry.modules().forEach(m -> ops.addAll(m.toolArgs().keySet()));
        ops.sort(String::compareTo);
        try {
            return json.writeValueAsString(Map.of("type", "object", "properties", Map.of(
                    "op", Map.of("type", "string", "enum", ops),
                    "args", Map.of("type", "object", "description", "op별 인자(설명 참고)")),
                    "required", List.of("op", "args"), "additionalProperties", false));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String progressLabel() {
        return "변경안 확인 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        String type = args.path("op").asText(null);
        if (type == null || !registry.knows(type)) {
            throw new IllegalArgumentException("op는 목록 중 하나여야 한다");
        }
        ChangeModule module = registry.modules().stream().filter(m -> m.opTypes().containsKey(type)).findFirst().orElseThrow();
        ChangeOp op = module.fromToolArgs(type, args.path("args"));
        ChangeCheck check = bundles.safeCheck(ws.userId(), op);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", check.code());
        if (!check.isOk()) {
            out.put("message", check.message());
            return write(out);
        }
        if (ws.hasTarget(check.op().targetKey())) {
            out.put("result", ChangeCheck.CONFLICT);
            out.put("message", "같은 대상의 변경이 이미 제안 목록에 있다 — 바꾸려면 withdraw_change 후 다시 제안한다");
            return write(out);
        }
        TurnWorkspace.Staged staged = ws.stage(check.op(), true, "PROPOSED", null, null);
        out.put("itemNo", staged.no());
        out.put("summary", check.op().summary());
        out.put("state", "제안됨(적용 전)");
        List<Map<String, Object>> linked = new ArrayList<>();
        try {
            for (ChangeModule.LinkedChange l : module.linked(ws.userId(), check.op())) {
                if (ws.hasTarget(l.op().targetKey())) {
                    continue;
                }
                TurnWorkspace.Staged ls = ws.stage(l.op(), l.stored(), l.stored() ? "STORED_LINK" : "INFERRED_LINK",
                        staged.no(), l.reason());
                linked.add(Map.of("itemNo", ls.no(), "summary", l.op().summary(), "selected", l.stored(), "reason", l.reason()));
            }
        } catch (RuntimeException e) {
            out.put("linkedNote", "관련 변경을 찾지 못했다(조회 실패)");
        }
        if (!linked.isEmpty()) {
            out.put("linked", linked);
        }
        return write(out);
    }

    private String write(Map<String, Object> out) {
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
