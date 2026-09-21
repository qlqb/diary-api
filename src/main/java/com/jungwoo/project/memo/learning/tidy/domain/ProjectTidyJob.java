package com.jungwoo.project.memo.learning.tidy.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * "이 프로젝트 자료 정리" 요청 하나. project_tidy_jobs.
 *
 * <p>사용자가 명시적으로 누른 것만 여기 들어온다 — 자료 분석이 끝났다는 이유로 만들어지지
 * 않는다. 프로젝트마다 열린 작업은 하나이고 그것을 DB가 지킨다(부분 유일 인덱스).
 *
 * <p>generation은 같은 프로젝트에서 요청이 다시 들어올 때마다 오른다. 결과 저장은 임대 토큰과
 * 세대를 함께 대조하므로, 버린 뒤 늦게 끝난 작업이 정리안을 되살리지 못한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTidyJob {

    private Long jobId;
    private Long userId;
    private Long courseId;
    private Long generation;
    private TidyJobStatus status;
    private Long baseTreeVersion;
    /** 이 요청이 갈아치우려는 기존 정리안. [새 자료 반영해 다시 정리]에서만 값이 있다. */
    private Long previousProposalId;
    private String inputSnapshotJson;
    private Integer attempt;
    private Integer maxAttempts;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long leaseToken;
    private Long resultProposalId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
