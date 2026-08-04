package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiResponseType;

import java.util.List;

/** message.completed SSE 이벤트의 payload. 프론트는 이 값으로 최종 상태를 확정한다. */
public record AiTurnCompletedPayload(
        AiResponseType responseType,
        String reply,
        Long proposalId,
        List<AiProposalItemResponse> proposalItems,
        OfferAction offerAction,
        Long userMessageId,
        Long assistantMessageId
) {
}
