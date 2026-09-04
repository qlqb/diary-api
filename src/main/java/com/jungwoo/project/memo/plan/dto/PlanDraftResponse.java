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
 *
 * <p>계획 화면(/api/plans/draft)과 AI 대화(period_plan.ready)가 같은 모양을 쓴다. 어느 탭에서
 * 만들었든 같은 검토·확정 화면이 받는다.
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

    /**
     * 예전 화면 호환용. 지금은 targetMinutes와 같은 값이다 — 학습 예산은 서버가 가용시간과
     * 강도로 계산하고 모델이 조정하지 않는다.
     */
    private Integer baselineMinutes;

    /** 학습 예산(분). 추정 가용시간 × 강도 비율을 15분 단위로 내린 값. 소진할 할당량이 아니다. */
    private Integer targetMinutes;

    /** 예전 화면 호환용. 서버가 예산을 정하므로 항상 null이다. */
    private String targetMinutesReason;

    /** 계획 기간에서 고정 일정·지난 시간을 뺀 추정 남는 시간(분). */
    private Integer estimatedAvailableMinutes;

    /**
     * 남는 시간 추정의 근거 요약. 근거가 없어 기본 시간대(평일 19~22시, 주말 10~18시)를 쓴
     * 부분이 있으면 그 사실을 말한다 — 확정 사실처럼 보이지 않게 하기 위해서다.
     */
    private String availabilityConfidenceSummary;

    /** 추정 남는 시간에서 학습 예산을 뺀 여유(분). 휴식·변동에 남겨 둔 시간이다. */
    private Integer reservedBufferMinutes;

    /**
     * 강도 비율로 계산한 예산이 한 제안의 물리적 상한(항목 30개 × 120분 = 3,600분)을 넘어
     * 상한으로 깎였다. 8일 이상 계획에서 나온다 — 화면은 "이 기간의 남는 시간을 다 담지는
     * 못했다"고 말해야 한다. 실패가 아니다.
     */
    private boolean targetCappedByItemLimit;

    /** targetCappedByItemLimit일 때 담지 못한 시간(분). 남는 시간 × 강도 − 실제 예산. */
    private Integer uncoveredMinutes;

    /**
     * 추정 남는 시간이 0이라 항목을 만들지 않았다. proposal은 null이다. 화면은 실패가 아니라
     * "현재 추정으로는 배치 가능한 시간이 없다"와 가용시간 수정 경로를 보여준다.
     */
    private boolean noAvailableTime;

    private String suggestedTitle;

    private String goalSummary;

    /** 항목 목록. 사용자는 여기서 체크를 풀어 부하를 조절한다. noAvailableTime이면 null. */
    private AiProposalResponse proposal;
}
