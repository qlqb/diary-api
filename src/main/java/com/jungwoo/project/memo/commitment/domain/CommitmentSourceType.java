package com.jungwoo.project.memo.commitment.domain;

/**
 * 약속이 어디서 만들어졌는지.
 *
 * <p>클라이언트가 보내는 값이 아니다. 직접 생성 API는 서버가 {@link #MANUAL}을 넣고,
 * AI 후보 승인은 서비스 내부에서 {@link #AI_SUGGESTION_APPROVED}를 넣는다. 요청 payload에
 * 이 필드를 열어 두면 사용자가 아무 약속에나 "AI가 만든 것" 딱지를 붙일 수 있는데,
 * 그러면 나중에 "AI가 얼마나 맞게 뽑았나"를 이 값으로 볼 수 없게 된다.
 */
public enum CommitmentSourceType {

    /** 사용자가 일정 화면에서 직접 입력. */
    MANUAL,

    /** AI가 대화에서 뽑은 후보를 사용자가 검토하고 승인. */
    AI_SUGGESTION_APPROVED
}
