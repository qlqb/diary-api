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

    /** Planning Agent가 학습 추천을 배치해 만든 경우 그 학습 topic. 학습과 무관하면 null. */
    private Long topicId;

    /**
     * 이 조각이 속한 프로젝트(courses). topicId와 다른 축이다 — topic은 자료 구조 분석을
     * 적용해야만 생기지만, 프로젝트는 만들자마자 존재하므로 자료가 없는 프로젝트의 실행도
     * 여기로 연결된다. 프로젝트와 무관한 조각은 null.
     */
    private Long courseId;

    /**
     * 이 조각을 처음 만들어낸 계획 확정(plan_versions). 생성 출처이며 이후 바뀌지 않는다.
     *
     * NULL이 정상값이다 — 직접 추가한 조각과 AI 단건 추천을 승인해 만든 조각은 전부 NULL이다.
     * "현재 어느 계획에 속하는가"는 이 필드가 답하지 않는다. 그건 계획의 스냅샷이 답한다.
     */
    private Long planVersionId;

    /** 분할 등으로 생겨난 조각의 원본. */
    private Long sourceExecutionItemId;

    private String title;

    private String description;

    /** UNSCHEDULED / DATE_ONLY / TIME_FIXED. 아래 세 필드의 유효성을 결정한다. */
    private PlacementType placementType;

    private LocalDate scheduledDate;

    private LocalDateTime scheduledStartAt;

    private LocalDateTime scheduledEndAt;

    /**
     * 이 시각까지 끝나야 계획상 의미가 있는 조각의 마감. 대개 "다음 수업 시작 시각"이다.
     *
     * scheduledEndAt과 다른 축이다 — 그쪽은 "언제 하기로 배치됐는가"(결과)이고 이쪽은
     * "언제까지여야 하는가"(제약)다. 롤링 배치가 SchedulingTask.deadline으로 넘겨
     * Timefold의 HARD 제약이 되며, 마감 안에 넣을 수 없으면 억지로 밀어넣지 않고
     * 미배치로 남긴다.
     *
     * 날짜가 아니라 시각이다. 수요일 10시 수업이 마감이면 수요일 새벽은 아직 쓸 수 있다.
     * 근거가 있을 때만 채운다 — "그래야 할 것 같아서" 붙인 마감은 배치를 조용히 망가뜨린다.
     */
    private LocalDateTime deadlineAt;

    /**
     * 날짜를 아직 정하지 않은(UNSCHEDULED) 조각의 목표 기간. scheduledDate에서 파생된
     * 중복이 아니라, scheduledDate가 없는 조각이 "언제까지의 계획에 속하는가"를 담는
     * 독립 정보다. 이게 없으면 8일 이상 계획을 확정한 직후 기간 조회에서 전부 사라진다.
     *
     * 둘은 항상 함께 채워지거나 함께 비고, UNSCHEDULED가 아니면 반드시 비어 있다
     * (chk_execution_items_planning_range).
     */
    private LocalDate planningStartDate;

    private LocalDate planningEndDate;

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
