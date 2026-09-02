package com.jungwoo.project.memo.ai.dto;

/**
 * 클라이언트가 이번 메시지에 기대하는 처리 방식. 내부 요청 계약이며 사용자에게 노출되는
 * 대화/제안 모드 스위치가 아니다 — 화면에는 항상 같은 입력창/전송 버튼만 있고, OFFER 버튼을
 * 눌렀을 때 프론트가 CREATE_PROPOSAL을 실어 보낼 뿐이다.
 */
public enum RequestedAction {
    /** 일반 전송. 모델이 CHAT/OFFER/PROPOSAL 중 스스로 판단한다. */
    AUTO,

    /** OFFER 카드의 "이 내용으로 계획 초안 만들기" 버튼을 눌렀을 때. */
    CREATE_PROPOSAL
}
