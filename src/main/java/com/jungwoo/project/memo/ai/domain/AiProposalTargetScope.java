package com.jungwoo.project.memo.ai.domain;

/**
 * ai_proposals.target_scope 체크 제약과 1:1 대응한다. ai_conversations.scope도 같은 enum을
 * 공유한다(둘 다 "이 대화/제안이 무엇을 대상으로 하는가"라는 같은 개념이라 분리하지 않았다).
 *
 * MATERIAL/LEARNING/PLANNING은 ai_conversations.scope 전용 값이다 — ai_proposals.target_scope
 * 체크 제약에는 추가하지 않았다(Planning Agent의 제안도 결국 실행 조각을 만들므로 EXECUTION을
 * 그대로 쓴다).
 */
public enum AiProposalTargetScope {
    PLAN,
    TODAY,
    EXECUTION,
    CONTEXT,
    MIXED,
    MATERIAL,
    LEARNING,
    PLANNING
}
