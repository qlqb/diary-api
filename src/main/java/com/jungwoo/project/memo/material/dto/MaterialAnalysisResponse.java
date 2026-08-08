package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MaterialAnalysisResponse {

    private Long analysisId;
    private Long courseId;
    private Long materialId;
    private MaterialAnalysisStatus status;
    /** editedJson이 있으면 그것을, 없으면 analysisJson을 파싱한 현재 유효한 내용. */
    private MaterialAnalysisPayload payload;
    private String failureReason;
    private Integer createdTopicCount;
    private LocalDateTime createdAt;
    private LocalDateTime appliedAt;
}
