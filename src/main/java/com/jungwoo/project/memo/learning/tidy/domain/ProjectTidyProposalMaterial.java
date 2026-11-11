package com.jungwoo.project.memo.learning.tidy.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 정리안 하나가 근거로 삼은 자료. project_tidy_proposal_materials.
 *
 * <p>적용 직전에 이 행의 해시·분석판을 지금 값과 대조한다. 검토하는 동안 자료가 다시 분석됐거나
 * 파일이 바뀌었으면 적용하지 않는다 — 사용자가 본 근거와 다른 것을 적용하게 되기 때문이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTidyProposalMaterial {

    private Long id;
    private Long proposalId;
    private Long userId;
    private Long materialId;
    private String fileHash;
    private Integer analysisVersion;
    /** 그 자료가 가진 구간 수. */
    private Integer sectionCount;
    /** 그중 실제로 모델에게 보낸 수. sectionCount보다 적으면 부분 검토다. */
    private Integer reviewedCount;
    private boolean included;
    private TidyExcludeReason excludeReason;
}
