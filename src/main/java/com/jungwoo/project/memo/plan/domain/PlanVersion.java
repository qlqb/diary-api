package com.jungwoo.project.memo.plan.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 특정 시점에 확정한 계획의 불변 스냅샷. plan_versions 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 이 엔티티는 INSERT된 뒤 절대 바뀌지 않는다. 현재 상태는 execution_items가 유일하게
 * 소유하고, 여기에는 "그때 무엇을 하기로 했는가"만 남는다. 따라서 itemsSnapshot의 값 복사는
 * 이중 원본이 아니라 의도적인 역사 보존이다.
 *
 * 불변성은 문서가 아니라 PlanVersionMapper에 update/delete 메서드가 없다는 사실로 강제한다.
 * setter는 Lombok @Data 때문에 생기지만, 그것으로 DB를 바꿀 경로가 없다.
 *
 * itemsSnapshot은 원본 JSON 문자열 그대로 담는다(ai_proposal_items.original_payload와 같은
 * 방식). 구조화된 접근이 필요하면 PlanSnapshotCodec으로 List&lt;PlanSnapshotItem&gt;을 얻는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanVersion {

    private Long planVersionId;

    private Long userId;

    /**
     * 같은 계획의 여러 판(version)을 묶는 키. 지금은 확정할 때마다 새 UUID가 생기고
     * version은 항상 1이다 — 재계획은 1차 범위 밖이다(11-period-plan.md §1-5).
     */
    private String planKey;

    private Integer version;

    private LocalDate startDate;

    private LocalDate endDate;

    private String title;

    /** 이 기간에 무엇을 이루려 했는가. 없으면 null. */
    private String goalSummary;

    /**
     * 확정 당시의 계획 강도. 회고에서 "집중으로 잡았는데 절반만 했다"를 판단하는 근거이고,
     * 다음 계획의 기본 강도를 여기서 이어받는다(11-period-plan.md §5-1-2).
     * 강도 없이 만든 계획이 있을 수 있으므로 null 허용이다.
     */
    private PlanIntensity intensity;

    /**
     * 확정 당시 AI에게 준 목표 학습 시간(분).
     *
     * intensity에서 매번 다시 계산하지 않고 값을 저장한다 — 프리셋 숫자는 실사용 후
     * 조정할 예정이고, 그때 과거 계획의 근거가 소급 변조되면 안 된다.
     */
    private Integer targetMinutes;

    /** PlanSnapshotItem 배열의 JSON. 현재 execution_items와 동기화하지 않는다. */
    private String itemsSnapshot;

    /**
     * 이 계획을 만들어낸 ai_proposals.proposal_id. UNIQUE 제약이 걸려 있어 같은 제안을
     * 두 번 확정할 수 없다. 제안 없이 만든 계획은 null이다.
     */
    private Long sourceProposalId;

    private LocalDateTime confirmedAt;
}
