package com.jungwoo.project.memo.scheduling.domain;

/**
 * 막힌 시간 하나가 어느 원본에서 왔는가.
 *
 * <p>{@link AvailabilitySource}와 다른 축이다. 그쪽은 "왜 이 시간이 후보가 됐는가"(추정의
 * 근거 등급)이고, 이쪽은 "이 막힌 시간의 원본 행이 무엇인가"(추적)다. 계획 생성이 가용시간
 * 추정을 근거로 제시하려면 계산에 실제로 들어간 입력을 가리킬 수 있어야 하는데, 라벨
 * 문자열만으로는 어느 행인지 알 수 없다.
 */
public enum BusySource {

    /** 이미 시각이 박힌 실행 조각. id는 execution_item_id. */
    EXECUTION_ITEM,

    /** 반복 일정(수업·근무)의 발생분. id는 routine_id — 발생분 자체에는 행이 없다. */
    ROUTINE_OCCURRENCE,

    /** 일회성 약속. id는 commitment_id. */
    COMMITMENT,

    /** 사용자가 미리보기에서 고정한 다른 후보 항목. id는 proposal_item_id. */
    PINNED_PROPOSAL_ITEM
}
