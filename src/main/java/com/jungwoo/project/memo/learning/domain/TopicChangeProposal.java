package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자료 정리 변경안. topic_change_proposals.
 *
 * <p>opsJson은 서버가 검증을 끝낸 작업 목록(LINK/ADD/RENAME/MOVE/MERGE/SPLIT)이고,
 * baseTreeVersion은 이 변경안을 만들 때 본 트리의 버전이다. 적용 시 현재 버전과 다르면
 * 적용하지 않고 CONFLICT로 남긴다 — 새 분석으로 사용자 편집을 덮지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicChangeProposal {

    private Long proposalId;
    private Long userId;
    private Long courseId;
    private Long materialId;
    private Long jobId;
    private String fileHash;
    private Long baseTreeVersion;
    private TopicChangeProposalStatus status;
    private String summaryJson;
    private String opsJson;
    private String appliedOpsJson;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
