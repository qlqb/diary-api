package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * requestedAction=AUTO(기본)이면 message가 이번 사용자 턴의 원문이다.
 * requestedAction=CREATE_PROPOSAL이면 OFFER 카드의 버튼 클릭이므로 message는 비워도 되고,
 * 대신 sourceMessageId(그 OFFER를 만든 자신의 이전 USER 메시지)를 반드시 넣는다. 여기에
 * 그 카드가 보여주던 periodStartDate/periodEndDate도 함께 넣는다 — 이 두 날짜가 이번 계획의
 * 확정 기간이고, 모델은 이 단계에서 기간을 다시 판단하지 않는다.
 * requestedAction=CREATE_PERIOD_PLAN이면 기간 계획 OFFER 카드의 버튼 클릭이다. periodPlan에
 * 그 카드가 들고 있던 기간·강도·대상 프로젝트를 그대로 되돌려 보낸다(서버가 다시 검증한다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessageRequest {

    private String message;

    private AiProposalTargetScope scope;

    private Long focusId;

    @Builder.Default
    private RequestedAction requestedAction = RequestedAction.AUTO;

    private Long sourceMessageId;

    /**
     * requestedAction=CREATE_PROPOSAL에서만 값을 가지며 그때는 필수다(둘 다). OFFER 카드가
     * 보여준 기간을 사용자가 보고 누른 값이라 이번 턴의 계획 기간 확정값이다. AUTO나
     * CREATE_PERIOD_PLAN 요청이 이 값을 실어 보내면 조용히 무시하지 않고 400으로 막는다 —
     * 어느 단계가 기간을 정하는지 흐릿해지면 같은 사실을 두 곳에서 판단하게 된다.
     */
    private LocalDate periodStartDate;

    private LocalDate periodEndDate;

    /** requestedAction=CREATE_PERIOD_PLAN에서만 값을 가진다. */
    private PeriodPlanRequest periodPlan;

    @NotBlank(message = "idempotencyKey는 필수입니다")
    private String idempotencyKey;
}
