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
    private Long todoId;
    private LocalDate blockDate;
    private String title;
    private ScheduleBlockType blockType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String memo;
    private ScheduleOriginType originType;
    private Boolean modifiedAfterCreation;
    private Boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
