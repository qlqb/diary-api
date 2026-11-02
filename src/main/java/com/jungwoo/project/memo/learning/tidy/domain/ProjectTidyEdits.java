package com.jungwoo.project.memo.learning.tidy.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 검토 중 사용자가 고친 것. project_tidy_edits. 정리안 하나에 한 행.
 *
 * <p><b>검토 초안이지 트리 변경이 아니다.</b> 여기 저장돼도 학습 구조는 그대로다. 저장하는
 * 이유는 새로고침·탭 이동으로 고친 것이 사라지지 않게 하기 위해서다.
 *
 * <p>editRevision은 저장할 때마다 오른다. 적용 요청이 이 값을 싣고 어긋나면 적용하지 않는다 —
 * 다른 탭에서 먼저 고친 것을 모른 채 덮지 않기 위해서다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTidyEdits {

    private Long proposalId;
    private Long userId;
    /** {changeId: {"excluded": true, "title": "..."}} */
    private String editsJson;
    private Long editRevision;
    private LocalDateTime updatedAt;
}
