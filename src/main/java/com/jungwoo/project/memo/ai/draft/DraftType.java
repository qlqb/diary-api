package com.jungwoo.project.memo.ai.draft;

/**
 * 진행 중 요청(draft)의 종류. 셋 모두 draft 상태 관리(생성·routing·RETYPE·readiness)에 참여한다.
 *
 * <p>proposal로 바꾸는 방식은 다르다. ROUTINE/SCHEDULE은 ai_schedule_suggestions 후보가 되고,
 * PERIOD_PLAN은 기존 기간 계획 OFFER(CREATE_PERIOD_PLAN 버튼)로 넘어간다 — 계획 생성기가
 * 모델을 한 번 더 부르는 경로라 같은 턴 안에서 만들지 않는다.
 *
 * <p>PERIOD_PLAN을 draft 밖에 두지 않는 이유: "PERIOD_PLAN으로 잘못 들어감 → 그냥 루틴만"이라는
 * 의도 전환(원래 버그의 절반)을 RETYPE 하나로 처리하기 위해서다.
 */
public enum DraftType {
    CREATE_ROUTINE,
    CREATE_SCHEDULE,
    CREATE_PERIOD_PLAN
}
