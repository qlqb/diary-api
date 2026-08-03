package com.jungwoo.project.memo.ai.domain;

/** ai_proposal_items.status 체크 제약과 1:1 대응한다. */
public enum AiProposalItemStatus {
    PROPOSED,
    APPLIED,
    MODIFIED_APPLIED,
    DISMISSED,
    EXPIRED
}
