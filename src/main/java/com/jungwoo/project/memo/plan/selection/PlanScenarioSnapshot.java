package com.jungwoo.project.memo.plan.selection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 계획 초안이 기대는 변경(가정 상태)의 불변 스냅샷 — 초안을 만들 때 쓴 변경 묶음의 리비전·선택 항목·정규화한 op 내용. 미리보기·확정은
 * 지금 묶음을 다시 읽지 않고 이 값으로 같은 상태를 만든다. 묶음이 바뀌었으면(리비전·선택) 이 초안은 재계산 대상이다.
 *
 * @param summaries 화면에 보일 "이 계획이 기대는 변경" 문장
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanScenarioSnapshot(Long bundleId, int revision, List<Long> itemIds, String fingerprint, List<Op> ops,
                                   List<String> summaries) {

    public PlanScenarioSnapshot {
        itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        ops = ops == null ? List.of() : List.copyOf(ops);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Op(String type, String json) {
    }
}
