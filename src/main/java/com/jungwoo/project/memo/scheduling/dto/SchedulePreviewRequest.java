package com.jungwoo.project.memo.scheduling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/ai/proposals/{proposalId}/schedule-preview 요청. horizonStart/horizonEnd를
 * 비우면 오늘부터 최대 7일 범위를 기본으로 쓴다. 이 요청은 OpenAI를 호출하지 않는다 —
 * Timefold 재계산만 수행한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchedulePreviewRequest {
    private LocalDate horizonStart;
    private LocalDate horizonEnd;
    private List<AvailabilityOverrideRequest> availabilityOverrides;
    private List<ScheduleItemOverride> items;
}
