package com.jungwoo.project.memo.ai.domain;

/** user_contexts.source_type 체크 제약과 1:1 대응한다. */
public enum ContextSourceType {
    /** 사용자가 직접 확정(향후 직접 입력 화면 등). */
    USER_CONFIRMED,

    /** AI 변경 후보(ai_context_change_suggestions)를 사용자가 승인. */
    AI_SUGGESTION_APPROVED
}
