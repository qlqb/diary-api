package com.jungwoo.project.memo.ai.domain;

/** ai_proposal_items.item_type 체크 제약과 1:1 대응한다. 이번 범위는 EXECUTION_ITEM만 쓴다. */
public enum AiProposalItemType {
    PLAN_ITEM,
    EXECUTION_ITEM,
    CONTEXT_ITEM
}
