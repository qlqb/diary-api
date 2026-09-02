package com.jungwoo.project.memo.ai.domain;

/**
 * 모델이 이번 턴에 대해 스스로 내린 판단. 화면에 보여줄 상태(AiResponseType)를 모델이 직접
 * 정하지 않는다 — 모델은 이 판단만 반환하고, requestedAction과 조합해 실제 responseType으로
 * 바꾸는 것은 서버(AiConversationService.resolveTurn)의 몫이다.
 *
 * PROPOSAL_READY는 실제로 요청받았을 때(requestedAction=CREATE_PROPOSAL)만 서버가 그대로
 * 반영한다 — 일반 상담 메시지(AUTO)에서 모델이 이 값을 반환해도 서버가 안전하게 OFFER로
 * 낮춘다. 계획 초안 생성 권한은 화면의 생성 버튼 클릭(CREATE_PROPOSAL)에만 있다.
 */
public enum AiModelDecision {

    /** 일반 상담 답변. 계획 생성 제안이 없다. */
    CHAT,

    /** 계획 생성에 필요한 정보가 부족하다. clarifyingQuestion으로 되묻는다. */
    ASK_CLARIFICATION,

    /** 계획을 만들 정보는 충분하다. 사용자에게 생성 버튼을 보여줄 수 있다. 실행 조각은 아직 만들지 않는다. */
    OFFER_PROPOSAL,

    /** 실제 계획 초안 항목을 생성한다. requestedAction=CREATE_PROPOSAL 요청에서만 서버가 허용한다. */
    PROPOSAL_READY
}
