package com.jungwoo.project.memo.ai.domain;

/** user_contexts.source_type 체크 제약과 1:1 대응한다. */
public enum ContextSourceType {
    /** 사용자가 직접 확정(향후 직접 입력 화면 등). */
    USER_CONFIRMED,

    /** AI 변경 후보(ai_context_change_suggestions)를 사용자가 승인. */
    AI_SUGGESTION_APPROVED,

    /** 상담 턴이 사용자 발화에서 바로 저장했다(승인 팝업 없음, 바로 고칠 수 있다). */
    CONSULT_AUTO,

    /** 사용자가 "조금 달라요"나 기억 화면에서 직접 고쳤다. */
    USER_EDITED,

    /** 점검 활동의 자기평가. */
    SELF_CHECK
}
