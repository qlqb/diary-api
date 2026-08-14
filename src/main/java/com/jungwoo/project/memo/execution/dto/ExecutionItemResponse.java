package com.jungwoo.project.memo.execution.dto;

import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
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
public class ExecutionItemResponse {

    private Long executionItemId;
    private Long userId;
    private Long planItemId;
    private Long topicId;
    /** 이 조각이 속한 프로젝트. 화면에서 프로젝트 이름표를 붙이는 데 쓴다. */
    private Long courseId;
    private Long sourceExecutionItemId;
    private String title;
    private String description;
    private PlacementType placementType;
    private LocalDate scheduledDate;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    private Integer expectedMinutes;
    private ExecutionStatus status;
    private ExecutionPriority priority;
    private Integer orderIndex;
    private Long routineId;
    private ExecutionOriginType originType;
    private Boolean modifiedAfterCreation;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ExecutionItemResponse from(ExecutionItem item) {
        return ExecutionItemResponse.builder()
                .executionItemId(item.getExecutionItemId())
                .userId(item.getUserId())
                .planItemId(item.getPlanItemId())
                .topicId(item.getTopicId())
                .courseId(item.getCourseId())
                .sourceExecutionItemId(item.getSourceExecutionItemId())
                .title(item.getTitle())
                .description(item.getDescription())
                .placementType(item.getPlacementType())
                .scheduledDate(item.getScheduledDate())
                .scheduledStartAt(item.getScheduledStartAt())
                .scheduledEndAt(item.getScheduledEndAt())
                .expectedMinutes(item.getExpectedMinutes())
                .status(item.getStatus())
                .priority(item.getPriority())
                .orderIndex(item.getOrderIndex())
                .routineId(item.getRoutineId())
                .originType(item.getOriginType())
                .modifiedAfterCreation(item.getModifiedAfterCreation())
                .version(item.getVersion())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
