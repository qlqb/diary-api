package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExecutionItemMapper {

    void insert(ExecutionItem item);

    ExecutionItem findByIdAndUserId(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId
    );

    List<ExecutionItem> findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("scheduledDate") LocalDate scheduledDate
    );

    /**
     * 날짜 범위 안의 실행 조각 전체(배치 종류·상태 무관). 주간 시간표처럼 여러 날짜를 한 번에
     * 보여줘야 하는 화면에서 쓴다. findByUserIdAndDate와 동일하게 상태로 거르지 않는다 —
     * 오늘 화면과 같은 원본을 같은 규칙으로 투영해야 하기 때문이다.
     */
    List<ExecutionItem> findByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 날짜 범위 + 계획 기간 조회. findByUserIdAndDateRange와 달리 아직 날짜를 정하지 않은
     * (UNSCHEDULED) 조각도 함께 잡는다 — 그 조각의 planning_start/end_date가 조회 범위와
     * 조금이라도 겹치면 포함한다.
     *
     * 둘을 하나로 합치지 않는 이유: 주간 시간표는 "그 칸에 놓인 것"만 그려야 하므로 날짜 없는
     * 조각이 섞이면 그릴 자리가 없다. 계획 화면은 반대로 "이 기간에 하기로 한 것"을 전부
     * 보여줘야 한다. 같은 테이블을 다른 질문으로 읽는 것이라 질의를 나눈다.
     *
     * 정렬은 배치된 항목이 먼저다(scheduled_date IS NULL이 0 → 1 순). 날짜가 정해진 것이
     * 먼저 눈에 들어와야 "언제 할지 아직 안 정한 것"이 남은 일감으로 읽힌다.
     */
    List<ExecutionItem> findByUserIdAndPlanningRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 날짜 범위 안의 TIME_FIXED 실행 조각. 7일 범위 일정 후보 배치에서 "이미 차지된 시간"으로
     * 쓴다. CANCELLED는 하지 않기로 결정한 시간이라 제외한다 — DONE/HOLD/PARTIAL/PLANNED는
     * 그 시간을 실제로 썼거나 여전히 그 자리를 차지하고 있으므로 포함한다.
     */
    List<ExecutionItem> findTimeFixedByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 해당 날짜의 현재 최대 order_index. AI 제안을 적용할 때 기존 항목 뒤에 이어 붙이는 용도.
     * 아무 항목도 없으면 null.
     */
    Integer findMaxOrderIndexByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("scheduledDate") LocalDate scheduledDate
    );

    /**
     * 기준일보다 과거이면서 status='PLANNED'인 항목. HOLD/DONE/CANCELLED는 제외한다.
     */
    List<ExecutionItem> findPendingBefore(
            @Param("userId") Long userId,
            @Param("baseDate") LocalDate baseDate
    );

    /**
     * 이동 전용 갱신. version이 일치할 때만 반영되고 version을 1 증가시킨다.
     */
    int updateForMove(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("scheduledDate") LocalDate scheduledDate,
            @Param("scheduledStartAt") LocalDateTime scheduledStartAt,
            @Param("scheduledEndAt") LocalDateTime scheduledEndAt
    );

    /**
     * 축소 전용 갱신. title/expectedMinutes 중 null이 아닌 것만 반영한다.
     */
    int updateForReduce(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("title") String title,
            @Param("expectedMinutes") Integer expectedMinutes
    );

    int updateStatusWithVersion(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("status") ExecutionStatus status
    );

    /**
     * 완료 전용 갱신. status를 DONE으로 바꾼다.
     */
    int completeWithVersion(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("version") Long version
    );

    int softDeleteWithVersion(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("version") Long version
    );

    /** Planning Agent 제안 적용 직후 학습 topic을 연결한다. */
    void updateTopicId(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("topicId") Long topicId
    );

    /** 프로젝트 대화에서 만든 제안을 적용한 직후 그 프로젝트를 연결한다. */
    void updateCourseId(
            @Param("executionItemId") Long executionItemId,
            @Param("userId") Long userId,
            @Param("courseId") Long courseId
    );

    /**
     * 프로젝트에 속한 실행 조각. 날짜 범위 밖이더라도 UNSCHEDULED(날짜 미정) 항목은 포함한다 —
     * 프로젝트 화면에서 "아직 언제 할지 안 정한 것"이 사라지면 안 되기 때문이다.
     */
    List<ExecutionItem> findByUserIdAndCourseId(
            @Param("userId") Long userId,
            @Param("courseId") Long courseId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
