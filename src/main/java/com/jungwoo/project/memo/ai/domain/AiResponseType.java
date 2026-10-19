package com.jungwoo.project.memo.ai.domain;

/**
 * 한 번의 AI 턴이 어떤 성격인지. ai_messages.response_type 체크 제약과 1:1 대응한다.
 *
 * CHAT과 OFFER는 proposalItems가 항상 비어 있어야 하고, PROPOSAL에서만 1~5개를 만든다.
 */
public enum AiResponseType {

    /** 인사, 잡담, 감정 표현, 정보가 부족한 입력 — 자연스러운 답변만 있고 제안은 없다. */
    CHAT,

    /** 계획을 짤 정보는 충분하지만 사용자가 아직 요청하지 않은 상태 — 초안 생성 여부를 물어본다. */
    OFFER,

    /** 사용자가 명시적으로 요청했거나 OFFER에 동의한 경우에만 — 편집 가능한 초안을 만든다. */
    PROPOSAL
}
