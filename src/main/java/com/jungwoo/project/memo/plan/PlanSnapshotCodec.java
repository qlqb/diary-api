package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * plan_versions.items_snapshot의 JSON ↔ List&lt;PlanSnapshotItem&gt; 변환과, 스냅샷을
 * 조립할 때 지켜야 할 최소 조건 검사.
 *
 * 스냅샷은 한 번 쓰면 고칠 수 없다(PlanVersionMapper에 update가 없다). 그래서 잘못된
 * 스냅샷은 나중에 바로잡는 것이 아니라 애초에 저장되지 않아야 하고, 그 방어선이 여기다.
 */
@Component
public class PlanSnapshotCodec {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 확정된 execution_items를 스냅샷 항목으로 옮긴다.
     *
     * courseTitle은 표시용 복사본이라 호출자가 넘긴다 — ExecutionItem은 course_id만 알고
     * 이름은 모른다. 이름을 모르면 null로 두고, 나중에 조회로 메우려 하지 않는다(그러면
     * 스냅샷이 "그때 이름"이 아니라 "지금 이름"이 된다).
     */
    public PlanSnapshotItem toSnapshotItem(ExecutionItem item, String courseTitle, String reason) {
        return new PlanSnapshotItem(
                item.getExecutionItemId(),
                item.getTitle(),
                item.getExpectedMinutes(),
                item.getPriority() != null ? item.getPriority().name() : null,
                item.getCourseId(),
                courseTitle,
                item.getTopicId(),
                item.getPlacementType(),
                item.getScheduledDate(),
                item.getScheduledStartAt(),
                item.getScheduledEndAt(),
                item.getPlanningStartDate(),
                item.getPlanningEndDate(),
                reason
        );
    }

    /**
     * 스냅샷을 JSON으로 굳힌다.
     *
     * executionItemId가 없는 항목은 여기서 거부한다. 회고는 이 id로만 현재 상태와 대조하므로,
     * 하나라도 비면 그 항목은 영원히 "계획에 있었는지 알 수 없는" 상태가 된다. 확정
     * 트랜잭션 전체를 실패시키는 편이 낫다.
     *
     * 항목이 하나도 없는 계획도 거부한다 — 빈 계획은 확정할 것이 없다는 뜻이고, 스냅샷이
     * 비어 있으면 회고가 전부 "계획 밖에서 한 일"이 된다.
     */
    public String toJson(List<PlanSnapshotItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("계획 스냅샷이 비어 있다");
        }
        for (PlanSnapshotItem item : items) {
            if (item.executionItemId() == null) {
                throw new IllegalArgumentException(
                        "계획 스냅샷 항목에 executionItemId가 없다: title=" + item.title());
            }
        }
        long distinct = items.stream().map(PlanSnapshotItem::executionItemId).distinct().count();
        if (distinct != items.size()) {
            throw new IllegalArgumentException("계획 스냅샷에 같은 executionItemId가 두 번 들어 있다");
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("계획 스냅샷 직렬화 실패", e);
        }
    }

    public List<PlanSnapshotItem> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PlanSnapshotItem>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("계획 스냅샷 역직렬화 실패", e);
        }
    }

    /** 이 스냅샷이 해당 프로젝트의 항목을 하나라도 담고 있는가. */
    public boolean containsCourse(String json, Long courseId) {
        if (courseId == null) {
            return true;
        }
        return fromJson(json).stream().anyMatch(item -> Objects.equals(item.courseId(), courseId));
    }
}
