package com.jungwoo.project.memo.execution.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 실행 조각 조정 사건. execution_item_events 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * before_state/after_state는 JSON 문자열로 저장한다 (DB CHECK json_valid).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemEvent {

    private Long executionItemEventId;

    private Long userId;

    private Long executionItemId;

    /** SPLIT 등으로 함께 생긴 연관 조각. */
    private Long relatedExecutionItemId;

    private ExecutionEventType eventType;

    private String reason;

    /** 바뀌기 전 상태. JSON 문자열. */
    private String beforeState;

    /** 바뀐 뒤 상태. JSON 문자열. */
    private String afterState;

    private Long beforeVersion;

    private Long afterVersion;

    private ExecutionEventActorType actorType;

    private LocalDateTime occurredAt;

    private LocalDateTime createdAt;
}
