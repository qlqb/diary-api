package com.jungwoo.project.memo.plan.domain;

/**
 * 취급을 그렇게 정한 근거의 출처. 닫힌 집합이다.
 *
 * ★ 모델이 여기 없는 타입을 만들어 내면 그 근거는 통째로 버린다. 근거의 종류를 모델이
 * 늘릴 수 있게 두면 "모델이 스스로 추측한 것"이 근거로 승격되는 통로가 생기고, 그러면
 * 근거 등급표(어떤 근거가 어디까지 취급을 낮출 수 있는가)가 무의미해진다.
 * 근거가 없으면 FULL이다 — 그것이 이 목록에 "모델 판단"이 없는 이유다.
 */
public enum EvidenceType {

    /** topic_progress.status / lastStudiedAt. 이 앱이 관찰한 사실. */
    PROGRESS,

    /** 다음 수업 시각(routines). */
    NEXT_CLASS,

    /** user_contexts의 확정된 맥락. refId가 context_id다. */
    CONTEXT,

    /** course_topics.user_mark. 사용자가 직접 말한 사실. */
    USER_MARK,

    /** 시험·과제 등 기한. */
    DEADLINE
}
