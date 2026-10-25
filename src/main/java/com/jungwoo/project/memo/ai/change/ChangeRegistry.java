package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 등록된 도메인 모듈을 op 이름으로 찾는다. 공통 처리부가 도메인별로 분기하지 않게 하는 유일한 자리다. */
@Component
public class ChangeRegistry {

    private final List<ChangeModule> modules;
    private final Map<String, ChangeModule> byType = new HashMap<>();
    /**
     * op 필드는 엄격하게 읽는다(모르는 필드 거절). 다만 op 안에 담은 검토 당시 엔티티 스냅샷(실행 항목·과제)은 파생 getter(예: 과제의
     * isCompleted)가 직렬화돼 되읽을 수 없으므로, 그 엔티티에만 모르는 필드를 무시하는 믹스인을 붙인다 — 도메인 클래스는 건드리지 않는다.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class LenientSnapshot {
    }

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .addMixIn(com.jungwoo.project.memo.execution.domain.ExecutionItem.class, LenientSnapshot.class)
            .addMixIn(com.jungwoo.project.memo.assignment.domain.CourseAssignment.class, LenientSnapshot.class);
    private final ObjectMapper writer = new ObjectMapper().findAndRegisterModules();

    public ChangeRegistry(List<ChangeModule> modules) {
        this.modules = modules.stream().sorted(Comparator.comparingInt(ChangeModule::lockOrder)).toList();
        for (ChangeModule module : this.modules) {
            module.opTypes().keySet().forEach(type -> {
                if (byType.put(type, module) != null) {
                    throw new IllegalStateException("op 이름이 두 모듈에 있다: " + type);
                }
            });
        }
    }

    public ChangeModule moduleOf(ChangeOp op) {
        ChangeModule module = byType.get(op.type());
        if (module == null) {
            throw new IllegalArgumentException("등록되지 않은 변경: " + op.type());
        }
        return module;
    }

    /** 잠금 순서대로. */
    public List<ChangeModule> modules() {
        return modules;
    }

    /** 저장된 op를 읽는다. 모르는 필드는 거절한다 — 필드가 조용히 버려지면 다른 변경이 적용된다. */
    public ChangeOp read(String type, String opJson) {
        ChangeModule module = byType.get(type);
        if (module == null) {
            throw new IllegalArgumentException("등록되지 않은 변경: " + type);
        }
        try {
            return json.readValue(opJson, module.opTypes().get(type));
        } catch (Exception e) {
            throw new IllegalArgumentException("변경을 읽지 못했다: " + type, e);
        }
    }

    public String write(ChangeOp op) {
        try {
            return writer.writeValueAsString(op);
        } catch (Exception e) {
            throw new IllegalStateException("변경을 저장 형식으로 바꾸지 못했다: " + op.type(), e);
        }
    }

    public boolean knows(String type) {
        return byType.containsKey(type);
    }
}
