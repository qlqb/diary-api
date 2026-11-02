package com.jungwoo.project.memo.ai.domain;

/**
 * 모델이 판단한 이번 제안의 목적. 서버는 이 값과 나머지 필드의 조합을 검증한 뒤에만 믿는다.
 *
 * <p>진입 탭(오늘/일정/프로젝트)이 아니라 사용자의 의도가 계획 경로를 정한다. "이번 주 계획
 * 짜줘"는 어느 탭에서 말했든 기간 계획이고, "자료구조 30분 추가해줘"는 어느 탭에서 말했든
 * 실행 조정이다. 그래서 이 값은 AiModelDecision(CHAT/ASK/OFFER/PROPOSAL_READY)과 별개다 —
 * decision은 "지금 무엇을 할 수 있는 상태인가"이고, purpose는 "무엇을 만들려는가"다.
 *
 * <p>문자열 키워드로 서버가 의도를 정하지 않는다. 모델이 이 필드를 명시하고, 서버는
 * PERIOD_PLAN이면 기간·강도·대상 프로젝트가 함께 있는지 검증한다(AiConversationService).
 */
public enum ProposalPurpose {

    /**
     * 기간 계획. 기간 안에 여러 프로젝트를 분배하거나 일정·가용시간을 보고 여러 실행 항목을
     * 구성해 달라는 요청. 공통 기간 계획 경로(PlanDraftService)로 가고, 검토 후
     * PlanVersion으로 확정된다. 항목 상한은 기간 계획의 15/30개다.
     */
    PERIOD_PLAN,

    /**
     * 실행 조정·단건 생성. "30분 추가", "줄여줘", "금요일로 옮겨줘", "빼줘". 기존 일반
     * AiProposalService 경로로 적용되며 새 항목과 조정 합계 5개가 상한이다.
     */
    EXECUTION_CHANGE
}
