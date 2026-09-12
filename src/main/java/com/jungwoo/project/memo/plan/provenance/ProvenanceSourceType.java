package com.jungwoo.project.memo.plan.provenance;

/**
 * 생성 회차에 모델 입력으로 <b>실제 제공한</b> 원본의 종류. 닫힌 집합이다.
 *
 * <p>★ 서버 계산 결과는 여기 없다. 가용시간 추정처럼 서버가 만든 값은
 * {@link ServerCalculation}이고, 그것을 "생성 시 제공한 정보"에 섞으면 사용자가 원본
 * 일정과 추정을 구분할 수 없게 된다(handoff §3.2).
 *
 * <p>★ 조회했지만 최종 입력에서 빠진 행은 어떤 타입으로도 들어오지 않는다. 출처 목록은
 * "본 것"이 아니라 "준 것"이다.
 */
public enum ProvenanceSourceType {

    /** courses 한 행. 프롬프트의 [대상 프로젝트] 줄. */
    COURSE,

    /** course_topics 한 행. 프롬프트의 [학습 항목] 줄. */
    TOPIC,

    /** course_notes의 평가 항목. */
    COURSE_NOTE,

    /**
     * 자료 분석(course_material_analyses)의 keyDates 한 건.
     *
     * <p>sourceId는 analysis_id다 — keyDate 자체에는 행 id가 없다. 쪽·문단 위치는 만들지
     * 않는다(handoff §3.1: 없는 페이지 번호는 만들지 않는다).
     */
    MATERIAL_KEY_DATE,

    /** 반복 일정(수업·근무)의 이 기간 발생분. sourceId는 routine_id다. */
    ROUTINE_OCCURRENCE,

    /** 일회성 약속(one_off_commitments) 한 행. */
    COMMITMENT,

    /** 이미 시각이 박힌 실행 조각. 가용시간에서 빠진 시간이다. */
    EXECUTION_ITEM_FIXED,

    /** 이 기간에 이미 잡혀 있는 실행 조각(날짜만/미정 포함). */
    EXECUTION_ITEM_PLANNED,

    /** 확정된 맥락(user_contexts) 한 줄. */
    USER_CONTEXT,

    /** 직전 계획 회고 요약. 특정 행 하나가 아니라 서버가 만든 요약이라 sourceId는 plan_version_id다. */
    PLAN_REVIEW,

    /**
     * 이번 요청에서 사용자가 방금 말한 조건(지시문·제목).
     *
     * <p>확정된 DB 맥락으로 가장하지 않는다(handoff §4). 저장된 행이 아니므로 sourceId는 없다.
     */
    TURN_INPUT
}
