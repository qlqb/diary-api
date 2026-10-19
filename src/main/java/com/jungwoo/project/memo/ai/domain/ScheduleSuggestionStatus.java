package com.jungwoo.project.memo.ai.domain;

/**
 * 일정 후보의 처리 상태.
 *
 * <p>PROPOSED에서만 APPLIED 또는 DISMISSED로 간다. 되돌아오지 않는다 — 결론이 난 후보를
 * 다시 열면 "이미 만든 약속이 또 만들어지는" 경로가 생긴다.
 */
public enum ScheduleSuggestionStatus {

    /** 만들어졌고 아직 사용자가 결론짓지 않았다. */
    PROPOSED,

    /** 사용자가 승인해 실제 원본이 만들어졌다. */
    APPLIED,

    /** 사용자가 적용하지 않기로 했다. 도메인 행은 만들어지지 않았다. */
    DISMISSED
}
