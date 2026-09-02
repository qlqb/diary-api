package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 계획 하나.
 *
 * ★ items_snapshot을 내려주지 않는다. 계획 화면의 항목 목록은 항상 현재 execution_items를
 * 봐야 한다(11-period-plan.md §5-3) — 스냅샷을 내려주면 화면이 그것을 쓰게 되고, 그러면
 * 항목을 옮겨도 계획 화면이 옛 상태를 보여준다. 스냅샷은 회고에서만 쓴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanResponse {

    private Long planVersionId;

    private String planKey;

    private Integer version;

    private LocalDate startDate;

    private LocalDate endDate;

    private String title;

    private String goalSummary;

    private PlanIntensity intensity;

    private Integer targetMinutes;

    private LocalDateTime confirmedAt;

    public static PlanResponse from(PlanVersion plan) {
        return PlanResponse.builder()
                .planVersionId(plan.getPlanVersionId())
                .planKey(plan.getPlanKey())
                .version(plan.getVersion())
                .startDate(plan.getStartDate())
                .endDate(plan.getEndDate())
                .title(plan.getTitle())
                .goalSummary(plan.getGoalSummary())
                .intensity(plan.getIntensity())
                .targetMinutes(plan.getTargetMinutes())
                .confirmedAt(plan.getConfirmedAt())
                .build();
    }
}
