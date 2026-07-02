package com.jungwoo.project.memo.schedule.dto;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import com.jungwoo.project.memo.schedule.domain.ScheduleOriginType;
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
public class ScheduleBlockResponse {

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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ScheduleBlockResponse from(ScheduleBlock block) {
        return ScheduleBlockResponse.builder()
                .scheduleBlockId(block.getScheduleBlockId())
                .userId(block.getUserId())
                .todoId(block.getTodoId())
                .blockDate(block.getBlockDate())
                .title(block.getTitle())
                .blockType(block.getBlockType())
                .startTime(block.getStartTime())
                .endTime(block.getEndTime())
                .memo(block.getMemo())
                .originType(block.getOriginType())
                .modifiedAfterCreation(block.getModifiedAfterCreation())
                .createdAt(block.getCreatedAt())
                .updatedAt(block.getUpdatedAt())
                .build();
    }
}
