package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;

import java.util.List;

/**
 * message.completed SSE 이벤트의 payload. 프론트는 이 값으로 최종 상태를 확정한다.
 *
 * @param periodPlanDraft requestedAction=CREATE_PERIOD_PLAN 턴에서만 값이 있다. 계획 화면의
 *                        /api/plans/draft 응답과 같은 모양이라 같은 검토 컴포넌트가 받는다.
 * @param quickReplies    되묻는 질문에 붙는 짧은 선택지(예: 강도 "가볍게/보통/집중"). 누르면
 *                        그 문장을 일반 메시지로 보낸다. 없으면 빈 배열.
 */
public record AiTurnCompletedPayload(
        AiResponseType responseType,
        String reply,
        Long proposalId,
        List<AiProposalItemResponse> proposalItems,
        OfferAction offerAction,
        Long userMessageId,
        Long assistantMessageId,
        PlanDraftResponse periodPlanDraft,
        List<String> quickReplies
) {
    /** 기간 계획도 선택지도 없는 기존 턴. */
    public AiTurnCompletedPayload(
            AiResponseType responseType, String reply, Long proposalId, List<AiProposalItemResponse> proposalItems,
            OfferAction offerAction, Long userMessageId, Long assistantMessageId
    ) {
        this(responseType, reply, proposalId, proposalItems, offerAction, userMessageId, assistantMessageId,
                null, List.of());
    }
}
