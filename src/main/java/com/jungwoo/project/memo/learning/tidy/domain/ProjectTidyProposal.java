package com.jungwoo.project.memo.learning.tidy.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 프로젝트 하나의 정리안. project_tidy_proposals.
 *
 * <p>자료 하나가 아니라 프로젝트에 연결된 <b>분석 완료 자료 전부</b>를 함께 보고 만든 것이다.
 * 그래서 같은 개념을 서로 다른 이름으로 건드리는 자료들을 한 번에 조정할 수 있다 — 자료별
 * 변경안을 이어 붙이면 풀리지 않던 문제다.
 *
 * <p>opsJson의 각 작업에는 changeId가 있다. 사용자 편집은 그 id로 따로 저장된다
 * (project_tidy_edits) — 정리안 자체는 사용자가 고쳐도 바뀌지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTidyProposal {

    private Long proposalId;
    private Long userId;
    private Long courseId;
    private Long jobId;
    private Long generation;
    /** 이 정리안 내용의 판 번호. 적용 요청이 이 값을 싣고, 어긋나면 적용하지 않는다. */
    private Long revision;
    private Long baseTreeVersion;
    private TidyProposalStatus status;
    private String summaryJson;
    private String opsJson;
    /** 실제로 검토한 자료·구간, 제외한 것과 사유, 한도로 못 본 범위. */
    private String scopeJson;
    private String appliedResultJson;
    private Long previousProposalId;
    private Long supersededByProposalId;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
}
