package com.jungwoo.project.memo.scheduling.dto;

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
public class SchedulePreviewResponse {
    private Long proposalId;
    private LocalDate horizonStart;
    private LocalDate horizonEnd;
    private List<AvailabilityWindowDto> availabilityWindows;
    private List<AvailabilityOverrideRequest> userOverrides;
    private List<PlacedItemDto> placedItems;
    private List<UnplacedItemDto> unplacedItems;
    private LocalDateTime computedAt;
}
