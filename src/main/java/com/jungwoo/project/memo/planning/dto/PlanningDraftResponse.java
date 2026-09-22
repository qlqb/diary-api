package com.jungwoo.project.memo.planning.dto;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class PlanningDraftResponse {
    private Long recommendationId;
    private Long proposalId;
    private Long courseId;
    private Long topicId;
    private String topicTitle;
    private LocalDate horizonStart;
    private LocalDate horizonEnd;
    private AiProposalResponse proposal;
    private SchedulePreviewResponse preview;
}
