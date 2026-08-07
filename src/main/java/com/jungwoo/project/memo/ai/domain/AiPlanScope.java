package com.jungwoo.project.memo.ai.domain;

/**
 * 모델이 decision=PROPOSAL_READY일 때 판단하는 계획 범위. 사용자가 말한 기간(오늘/이번 주/
 * 이번 달)에 따라 모델이 스스로 결정해 구조화 응답에 채운다. periodStartDate/periodEndDate
 * (AiTurnStructured)와 함께 서버가 계약을 검증한다(AiConversationService.periodViolationReason
 * 참고) — DAY면 두 날짜가 같아야 하고, WEEK/MONTH는 각각 최대 7일/31일 범위를 넘을 수 없다.
 * 이 검증은 requestedAction=CREATE_PROPOSAL + decision=PROPOSAL_READY일 때만 수행되며,
 * 위반이면 날짜를 조용히 되돌리지 않고 턴 전체를 실패(AI_GENERATION_FAILED)로 처리한다 —
 * "오늘 계획"을 물었는데 모델이 임의로 한 주 전체로 답을 부풀리는 것을 막는다.
 */
public enum AiPlanScope {
    /** 오늘/내일처럼 특정 하루 또는 특정 날짜. 불명확할 때의 서버 기본값이기도 하다(가장 좁게 잡는다). */
    DAY,
    /** 사용자가 "이번 주"/"주간"처럼 명시했을 때만. */
    WEEK,
    /** 사용자가 "이번 달"/"월간"처럼 명시했을 때만. */
    MONTH
}
