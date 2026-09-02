package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 계획 초안. 아직 execution_items도 plan_versions도 만들어지지 않았다 — 사용자가 확정해야
 * 실제 데이터가 생긴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDraftResponse {

    private Long proposalId;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer days;

    private PlanIntensity intensity;

    /** 프리셋이 제시한 기준선. 화면이 "조정됐다"를 판단하는 기준이다. */
    private Integer baselineMinutes;

    /** AI가 정한 최종 목표. 조정이 없었으면 baselineMinutes와 같다. */
    private Integer targetMinutes;

    /**
     * 기준선을 조정한 이유. 조정이 없었으면 null이고, 그때 화면은 이유 줄을 그리지 않는다.
     * 영속하지 않는 값이라 초안 응답에서만 볼 수 있다.
     */
    private String targetMinutesReason;

    private String suggestedTitle;

    private String goalSummary;

    /** 항목 목록. 사용자는 여기서 체크를 풀어 부하를 조절한다. */
    private AiProposalResponse proposal;
}
