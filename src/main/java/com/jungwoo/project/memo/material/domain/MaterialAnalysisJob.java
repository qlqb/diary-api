package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 백그라운드 분석 작업 한 건. material_analysis_jobs.
 *
 * <p>선점은 lease로 한다: 잡을 때마다 leaseToken이 1 오르고, 결과 저장은 전부 그 토큰을 대조한다.
 * 그래서 늦게 돌아온 이전 임대의 결과는 0행 UPDATE가 되어 조용히 버려진다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialAnalysisJob {

    /** CONTENT 작업의 course_id. NULL 대신 0 — UNIQUE 키에 들어가야 하기 때문이다. */
    public static final long NO_COURSE = 0L;

    private Long jobId;
    private Long userId;
    private Long materialId;
    private Long courseId;
    private AnalysisJobKind jobKind;
    private String fileHash;
    private Integer analysisVersion;
    private Integer priority;
    private AnalysisJobStatus status;
    private Integer attempt;
    private Integer maxAttempts;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long leaseToken;
    private Integer totalChunks;
    private Integer completedChunks;
    private String checkpointJson;
    private String errorCode;
    private String errorMessage;
    private Long resultRefId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;

    public boolean hasCourse() {
        return courseId != null && courseId != NO_COURSE;
    }
}
