package com.jungwoo.project.memo.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalApplyRequest {

    /** 실제로 수정된 항목만 포함한다. 포함되지 않은 항목은 원본 payload를 그대로 쓴다. */
    private List<EditedProposalItem> editedItems;

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
    }
}
