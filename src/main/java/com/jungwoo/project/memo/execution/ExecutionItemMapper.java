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
}
