package com.jungwoo.project.memo.schedule.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlock {

    private Long scheduleBlockId;
    private Long userId;
    private Long dailyPlanId;   // 1차-A: nullable. move 액션이 채우고, 전체 백필은 1차-C
    private Long todoId;
    private LocalDate blockDate;
    private String title;
    private ScheduleBlockType blockType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String memo;
    private ScheduleStatus status;   // 현재 상태만. MOVED/REDUCED는 상태가 아니라 이벤트
    private ScheduleOriginType originType;
    private Boolean modifiedAfterCreation;
    private Boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
