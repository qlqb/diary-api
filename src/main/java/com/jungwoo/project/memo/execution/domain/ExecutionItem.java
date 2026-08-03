package com.jungwoo.project.memo.execution.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 실행 조각. execution_items 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * Today와 Execution 화면의 공식 실행 원본이다. schedule_blocks에는 더 이상 쓰지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItem {

    private Long executionItemId;

    private Long userId;

    /** 어떤 계획 항목을 실행하는 조각인지. 계획 없이 만든 항목은 null. */
    private Long planItemId;

    /** 분할 등으로 생겨난 조각의 원본. */
    private Long sourceExecutionItemId;

    private String title;

    private String description;

    /** UNSCHEDULED / DATE_ONLY / TIME_FIXED. 아래 세 필드의 유효성을 결정한다. */
    private PlacementType placementType;

    private LocalDate scheduledDate;

    private LocalDateTime scheduledStartAt;

    private LocalDateTime scheduledEndAt;

    /** 예상 소요 시간(분). 실제 소요는 ExecutionRecord가 갖는다. */
    private Integer expectedMinutes;

    private ExecutionStatus status;

    private ExecutionPriority priority;

    /** 같은 날짜 안의 사용자/AI 정렬 순서. */
    private Integer orderIndex;

    /** 반복 루틴에서 생성된 경우의 원본 루틴. */
    private Long routineId;

    private ExecutionOriginType originType;

    /** AI 또는 루틴이 만든 항목을 사용자가 고쳤는지. MANUAL은 항상 false. */
    private Boolean modifiedAfterCreation;

    /**
     * 낙관적 락 전용 필드. 공식 변경마다 1 증가한다.
     * 클라이언트는 수정 요청 시 현재 알고 있는 version을 함께 보내고,
     * 서버는 이 값과 실제 값을 비교해 다르면 409 Conflict를 반환한다.
     */
    private Long version;

    private Boolean isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
