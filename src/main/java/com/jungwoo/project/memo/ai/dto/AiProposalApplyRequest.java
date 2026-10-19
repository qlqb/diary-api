package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalApplyRequest {

    /** 실제로 수정된 항목만 포함한다. 포함되지 않은 항목은 원본 payload를 그대로 쓴다. */
    private List<EditedProposalItem> editedItems;

    /** 사용자가 이 묶음에서 뺀(적용하지 않을) 항목 ID들. 실행 조각으로 만들지 않고 DISMISSED로 남긴다. */
    private List<Long> excludedItemIds;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EditedProposalItem {
        private Long proposalItemId;
        private String title;
        private String description;
        private Integer expectedMinutes;
        private String priority;
        /** null이면 원본 배치를 유지한다. 값을 주면 placementType/시각 세트를 통째로 교체한다. */
        private PlacementType placementType;
        private LocalDateTime scheduledStartAt;
        private LocalDateTime scheduledEndAt;
        /**
         * 이 항목이 최종적으로 배치될 날짜. null이면 TIME_FIXED 시각의 날짜 또는 제안 생성
         * 당시의 날짜를 그대로 쓴다. 7일 범위 배치 미리보기처럼 항목마다 다른 날짜로 확정된
         * 경우에만 채운다 — 기존 "오늘" 단일 제안 흐름에서는 사용하지 않는다.
         */
        private LocalDate scheduledDate;
    }
}
