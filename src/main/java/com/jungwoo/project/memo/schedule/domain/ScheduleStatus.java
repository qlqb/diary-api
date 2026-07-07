package com.jungwoo.project.memo.schedule.domain;

/**
 * 시간 블록의 현재 상태.
 *
 * status는 "현재 상태"만 표현한다.
 * MOVED, REDUCED는 상태가 아니라 "발생한 사건"이므로 여기 없다.
 * (이동/축소 후의 블록은 여전히 PLANNED다. 사건은 plan_item_events에 기록된다.)
 */
public enum ScheduleStatus {
    PLANNED,    // 계획됨 (기본값)
    DONE,       // 완료
    HOLD,       // 보류 중 (당분간 다시 묻지 않음)
    CANCELLED   // 안 하기로 결정
}
