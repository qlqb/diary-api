package com.jungwoo.project.memo.ai.dto;

/** OFFER 턴에서만 채워지는 단일 액션. 사용자가 누르면 requestedAction=CREATE_PROPOSAL로 재요청한다. */
public record OfferAction(
        String type,
        String label
) {
    public static OfferAction createProposal(String label) {
        return new OfferAction("CREATE_PROPOSAL", label);
    }
}
