package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 제안 카드 하나. edited_payload가 있으면 그 값을, 없으면 original_payload 값을 담는다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalItemResponse {

    private Long proposalItemId;
    private AiProposalItemStatus status;
    private String title;
    private String description;
    private Integer expectedMinutes;
    private String priority;
    private LocalDate targetDate;
    private PlacementType placementType;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    private Boolean modified;
    private Long createdItemId;
}
