package com.jungwoo.project.memo.ai.domain;

/** ai_proposals.target_scope 체크 제약과 1:1 대응한다. */
public enum AiProposalTargetScope {
    PLAN,
    TODAY,
    EXECUTION,
    CONTEXT,
    MIXED
}
