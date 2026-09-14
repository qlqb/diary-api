package com.jungwoo.project.memo.ai.dto;

/**
 * 클라이언트가 이번 메시지에 기대하는 처리 방식. 내부 요청 계약이며 사용자에게 노출되는
 * 대화/제안 모드 스위치가 아니다 — 화면에는 항상 같은 입력창/전송 버튼만 있고, OFFER 버튼을
 * 눌렀을 때 프론트가 그 OFFER의 type에 맞는 값을 실어 보낼 뿐이다.
 */
public enum RequestedAction {
    /** 일반 전송. 모델이 CHAT/OFFER/PROPOSAL 중 스스로 판단한다. */
    AUTO,

    /** OFFER(CREATE_PROPOSAL) 카드의 버튼을 눌렀을 때. 일반 제안·실행 조정, 5개 상한. */
    CREATE_PROPOSAL,

    /**
     * OFFER(CREATE_PERIOD_PLAN) 카드의 버튼을 눌렀을 때. 상담 모델을 부르지 않고 계획 화면과
     * 같은 PlanDraftService로 기간 계획 초안을 만든다(15/30개 상한, 검토 후 PlanVersion 확정).
     * periodPlan 필드가 필수다.
     */
    CREATE_PERIOD_PLAN
}
