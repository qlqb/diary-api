package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import com.jungwoo.project.memo.schedule.domain.ScheduleStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ScheduleBlockMapper {

    void insert(ScheduleBlock block);

    ScheduleBlock findByIdAndUserId(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId
    );

    List<ScheduleBlock> findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("blockDate") LocalDate blockDate
    );

    void update(ScheduleBlock block);

    // ===== 1차-A 도메인 액션 지원 =====

    /**
     * 이동(move) 전용 갱신: 날짜, 소속 DailyPlan, 시각(시간 지정 블록)만 변경.
     * status는 건드리지 않는다 — 이동 후에도 PLANNED 유지.
     */
    void updateForMove(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId,
            @Param("blockDate") LocalDate blockDate,
            @Param("dailyPlanId") Long dailyPlanId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 축소(reduce) 전용 갱신: 제목만 변경. status는 PLANNED 유지.
     */
    void updateTitle(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId,
            @Param("title") String title
    );

    /**
     * 상태 전이 전용 갱신 (hold, complete).
     */
    void updateStatus(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId,
            @Param("status") ScheduleStatus status
    );

    int completeIfNotDone(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId
    );

    /**
     * 기준 운영일 이전 block_date에 속한 PLANNED 블록 조회.
     */
    List<ScheduleBlock> findPendingBefore(
            @Param("userId") Long userId,
            @Param("baseDate") LocalDate baseDate
    );
}
