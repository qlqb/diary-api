package com.jungwoo.project.memo.scheduling.domain;

/**
 * 가용시간 후보 하나가 어디서 왔는지. execution_items나 대화 원문을 직접 노출하지 않고
 * 화면에 "왜 이 시간이 후보가 됐는지" 설명하기 위한 값이다.
 */
public enum AvailabilitySource {

    /** 기존 TIME_FIXED 실행 조각으로 이미 차지된 시간 근처에서 파생(현재는 busy로만 쓰고 후보 생성에는 미사용). */
    FIXED_SCHEDULE,

    /** 현재 대화에서 사용자가 직접 말한 제약(예: "화요일 저녁은 알바"). */
    CURRENT_CONVERSATION,

    /** 사용자가 이전에 확정한 장기 컨텍스트. ContextItem이 아직 없어 이번 구현에서는 사용하지 않는다. */
    USER_CONFIRMED_CONTEXT,

    /** 최근 실행 패턴에서 반복적으로 확인된 선호. 이번 구현에서는 사용하지 않는다(반복 근거 집계 미구현). */
    EXECUTION_PATTERN,

    /** 근거가 없을 때 쓰는 Asia/Seoul 기준 보수적 기본 활동 시간대. */
    DEFAULT_INFERENCE,

    /** 사용자가 미리보기 화면에서 직접 고친 예외("이 시간은 안 돼요" 또는 다시 허용). */
    USER_OVERRIDE
}
