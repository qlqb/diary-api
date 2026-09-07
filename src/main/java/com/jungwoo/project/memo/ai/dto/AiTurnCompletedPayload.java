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
 * @param systemNote      서버가 만든 확인 문장(SystemNotes). 이번 턴에 실제로 생긴 후보·맥락으로만
 *                        정해지고 모델이 만들거나 지울 수 없다. 생긴 것이 없으면 null. reply와
 *                        분리해 두어 화면이 구분해 보여준다
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
        List<String> quickReplies,
        String systemNote
) {
    /** 기간 계획도 선택지도 없는 기존 턴. */
    public AiTurnCompletedPayload(
            AiResponseType responseType, String reply, Long proposalId, List<AiProposalItemResponse> proposalItems,
            OfferAction offerAction, Long userMessageId, Long assistantMessageId
    ) {
        this(responseType, reply, proposalId, proposalItems, offerAction, userMessageId, assistantMessageId,
                null, List.of(), null);
    }

    /** 확인 문장이 없는 턴. systemNote가 생기기 전의 모양을 그대로 받는다. */
    public AiTurnCompletedPayload(
            AiResponseType responseType, String reply, Long proposalId, List<AiProposalItemResponse> proposalItems,
            OfferAction offerAction, Long userMessageId, Long assistantMessageId,
            PlanDraftResponse periodPlanDraft, List<String> quickReplies
    ) {
        this(responseType, reply, proposalId, proposalItems, offerAction, userMessageId, assistantMessageId,
                periodPlanDraft, quickReplies, null);
    }
}
