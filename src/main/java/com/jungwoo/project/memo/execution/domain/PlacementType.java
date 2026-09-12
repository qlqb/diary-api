package com.jungwoo.project.memo.execution.domain;

/**
 * 실행 조각이 얼마나 구체적으로 배치되었는지.
 *
 * execution_items의 chk_execution_items_placement 체크 제약과 1:1 대응한다.
 * - UNSCHEDULED : scheduled_date, scheduled_start_at, scheduled_end_at 모두 null
 * - DATE_ONLY   : scheduled_date만 not null
 * - TIME_FIXED  : scheduled_date, scheduled_start_at, scheduled_end_at 모두 not null
 */
public enum PlacementType {

    /** 날짜 미정. 언제 할지 아직 정하지 않은 상태. */
    UNSCHEDULED,

    /** 날짜만 지정. 하루 안의 시각은 미정. */
    DATE_ONLY,

    /** 날짜와 시작·종료 시각까지 지정. */
    TIME_FIXED
}
