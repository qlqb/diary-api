package com.jungwoo.project.memo.ai.domain;

/**
 * 모델이 decision=PROPOSAL_READY일 때 판단하는 계획 범위. 사용자가 말한 기간(오늘/이번 주/
 * 이번 달/직접 지정한 범위)을 어떻게 읽었는지를 나타낸다.
 *
 * <p>이 값은 계획 기간의 원본이 아니다 — 실제 대상 기간은 언제나 periodStartDate/
 * periodEndDate(AiTurnStructured) 두 날짜뿐이고, planScope는 그 두 날짜를 서버가 검증하기
 * 위한 의미 분류다(AiConversationService.periodViolationReason 참고).
 *
 * <p>기간 계약: DAY는 두 날짜가 같아야 하고, WEEK는 최대 7일, MONTH와 RANGE는 각각 최대
 * 31일 범위를 넘을 수 없다(기간형 계획 도메인의 1~31일과 같은 상한이다).
 * 이 검증은 requestedAction=CREATE_PROPOSAL + decision=PROPOSAL_READY일 때만 수행되며,
 * 위반이면 날짜를 조용히 되돌리지 않고 턴 전체를 실패(AI_GENERATION_FAILED)로 처리한다 —
 * "오늘 계획"을 물었는데 모델이 임의로 한 주 전체로 답을 부풀리는 것을 막는다.
 *
 * <p>사람이 기간을 말하는 방식마다 값을 늘리지 않는다(WEEKEND, TEN_DAYS, CUSTOM_WEEK 같은
 * 값을 추가하지 않는다) — 달력 표현은 enum보다 빨리 늘어난다. 하나의 달력 주/달로 표현할 수
 * 없는 기간은 전부 RANGE이고, 그 실제 범위는 periodStartDate/periodEndDate가 말한다.
 */
public enum AiPlanScope {
    /** 오늘/내일처럼 특정 하루 또는 특정 날짜. 불명확할 때의 서버 기본값이기도 하다(가장 좁게 잡는다). */
    DAY,
    /** 사용자가 "이번 주"/"다음 주"처럼 하나의 달력 주를 명시했을 때만. 최대 7일. */
    WEEK,
    /** 사용자가 "이번 달"/"월간"처럼 하나의 달력 월을 명시했을 때만. 최대 31일. */
    MONTH,
    /**
     * DAY/WEEK/MONTH 하나로 표현할 수 없는 사용자 지정 기간. 최대 31일.
     * "이번 주 토일이랑 다음 주까지", "오늘부터 열흘", "9월 5일부터 13일까지"처럼 여러 기간
     * 표현이 합쳐졌거나 사용자가 시작·종료를 직접 지정한 경우다. 장기 계획이라는 뜻이 아니라
     * "달력 단위에 맞지 않는 범위"라는 뜻이다.
     */
    RANGE
}
