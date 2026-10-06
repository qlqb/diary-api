package com.jungwoo.project.memo.scheduling.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Timefold가 배치에 성공한(또는 사용자가 고정한) 제안 항목 하나. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacedItemDto {
    private Long proposalItemId;
    private String title;
    private PlacementType placementType;
    private LocalDate scheduledDate;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    private String reason;
}
