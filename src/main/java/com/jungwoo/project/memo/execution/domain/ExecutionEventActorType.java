package com.jungwoo.project.memo.execution.domain;

/**
 * 사건을 일으킨 주체. execution_item_events.actor_type 체크 제약과 1:1 대응한다.
 */
public enum ExecutionEventActorType {
    USER,
    AI,
    SYSTEM,
    MIGRATION,
    UNKNOWN
}
