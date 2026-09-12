package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 계획 회고. 확정 당시의 스냅샷과 현재 상태를 대조한 결과다.
 *
 * ★ 퍼센트나 달성률을 담지 않는다. 화면도 계산하지 않는다 — 숫자를 나란히 보여주고
 * "15시간 중 9시간 30분을 했어요"처럼 문장으로 말한다. 비율은 못 한 쪽을 강조하게 된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanReviewResponse {

    private Long planVersionId;

    private String planKey;

    private String title;

    private LocalDate startDate;

    private LocalDate endDate;

    private PlanIntensity intensity;

    /** 확정 당시 AI가 정한 목표 시간. */
    private Integer targetMinutes;

    /** 스냅샷 항목들의 예상 시간 합. */
    private Integer plannedMinutes;

    /** 완료·일부 진행 항목의 실제 시간 합. 기록이 없으면 예상 시간으로 대신하지 않는다. */
    private Integer completedMinutes;

    private List<PlanReviewItem> items;

    /**
     * 회고 항목 하나.
     *
     * category가 주 분류이고 status가 단독으로 결정한다 — records는 status=DONE일 때
     * 완료/일부 진행을 가르는 데만 쓴다(11-period-plan.md §5-4). moved는 주 분류가 아니라
     * 동시에 성립할 수 있는 부가 플래그다.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanReviewItem {

        private Long executionItemId;

        private String title;

        private Long courseId;

        private String courseTitle;

        /** DONE / PARTIAL_DONE / REMAINING / UNPLACED / HOLD / EXCLUDED / OUTSIDE_PLAN / LEFTOVER */
        private PlanReviewCategory category;

        /** null / MOVED / SCHEDULED / UNPLACED_AGAIN. 주 분류와 함께 성립한다. */
        private PlanReviewMoveFlag moveFlag;

        private LocalDate plannedDate;

        private LocalDate currentDate;

        private Integer expectedMinutes;

        private Integer actualMinutes;

        /** 이 항목에 달린 기록 건수. 최신 1건만 판정에 썼다는 사실을 추적할 수 있게 한다. */
        private int recordCount;
    }
}
