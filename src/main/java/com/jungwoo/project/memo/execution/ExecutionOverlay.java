package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 아직 적용하지 않은 실행 항목 변경(가정 상태). executionItemId → 바뀐 항목(복사본). 새 항목은 음수 id, 삭제는 isDeleted=true.
 *
 * <p>조회 결과를 겹친 뒤, 원래 조회와 <b>같은 조건</b>으로 다시 거른다 — 예를 들어 배치 해제한 항목은 TIME_FIXED 조회에서 빠지고,
 * 다른 날로 옮긴 항목은 그 날 조회에서 빠진다. 조건을 부르는 쪽이 넘기므로 조회마다 규칙이 갈라지지 않는다.
 */
public final class ExecutionOverlay {

    public static final ExecutionOverlay NONE = new ExecutionOverlay(Map.of());

    private final Map<Long, ExecutionItem> changes;

    private ExecutionOverlay(Map<Long, ExecutionItem> changes) {
        this.changes = changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** 이 가정 상태가 그 할 일을 바꾸거나 새로 만들었는가. */
    public boolean touches(Long executionItemId) {
        return changes.containsKey(executionItemId);
    }

    /**
     * @param fromDb    원래 조회 결과
     * @param condition 원래 조회의 조건(바뀐 항목이 여전히 이 조회에 들어오는가)
     */
    public List<ExecutionItem> apply(List<ExecutionItem> fromDb, Predicate<ExecutionItem> condition) {
        if (changes.isEmpty()) {
            return fromDb;
        }
        List<ExecutionItem> out = new ArrayList<>();
        for (ExecutionItem item : fromDb) {
            if (!changes.containsKey(item.getExecutionItemId())) {
                out.add(item);
            }
        }
        for (ExecutionItem changed : changes.values()) {
            if (!Boolean.TRUE.equals(changed.getIsDeleted()) && condition.test(changed)) {
                out.add(changed);
            }
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Long, ExecutionItem> changes = new LinkedHashMap<>();

        public Builder put(ExecutionItem item) {
            changes.put(item.getExecutionItemId(), item);
            return this;
        }

        public ExecutionOverlay build() {
            return changes.isEmpty() ? NONE : new ExecutionOverlay(Collections.unmodifiableMap(new LinkedHashMap<>(changes)));
        }
    }
}
