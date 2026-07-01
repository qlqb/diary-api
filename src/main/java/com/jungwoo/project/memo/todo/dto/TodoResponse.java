package com.jungwoo.project.memo.todo.dto;

import com.jungwoo.project.memo.todo.domain.Todo;
import com.jungwoo.project.memo.todo.domain.TodoOriginType;
import com.jungwoo.project.memo.todo.domain.TodoPriority;
import com.jungwoo.project.memo.todo.domain.TodoStatus;
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
public class TodoResponse {

    private Long todoId;
    private Long userId;
    private LocalDate todoDate;
    private String title;
    private String content;
    private TodoStatus status;
    private TodoPriority priority;
    private TodoOriginType originType;
    private Boolean modifiedAfterCreation;
    private Long routineId;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TodoResponse from(Todo todo) {
        return TodoResponse.builder()
                .todoId(todo.getTodoId())
                .userId(todo.getUserId())
                .todoDate(todo.getTodoDate())
                .title(todo.getTitle())
                .content(todo.getContent())
                .status(todo.getStatus())
                .priority(todo.getPriority())
                .originType(todo.getOriginType())
                .modifiedAfterCreation(todo.getModifiedAfterCreation())
                .routineId(todo.getRoutineId())
                .completedAt(todo.getCompletedAt())
                .createdAt(todo.getCreatedAt())
                .updatedAt(todo.getUpdatedAt())
                .build();
    }
}
