package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 화면(카드)과 재접속 복원이 쓰는 묶음 모양. 저장된 행에서 만든다 — 도구 호출을 다시 실행하지 않는다.
 *
 * @param blocked 기대는 항목이 선택에서 빠져 이 항목만으로는 적용할 수 없다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeBundleView(Long bundleId, Long conversationId, Long sourceMessageId, String status, int revision,
                               List<Item> items, Map<String, Object> applyResult) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(Long itemId, int seq, String opType, String targetKey, String summary, List<Integer> dependsOn,
                       String origin, boolean selected, String status, String checkCode, String checkMessage,
                       boolean blocked, Map<String, Object> result) {
    }

    public List<Long> selectedIds() {
        return items.stream().filter(Item::selected).map(Item::itemId).toList();
    }
}
