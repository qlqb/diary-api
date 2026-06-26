package com.jungwoo.project.memo.todo.dto;

import com.jungwoo.project.memo.todo.domain.Todo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Todo 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoResponse {

    /** Todo ID */
    private Long todoId;

    /** 작성자 ID */
    private Long userId;

    /** Todo 날짜 */
    private LocalDate todoDate;

    /** 제목 */
    private String title;

    /** 메모 */
    private String content;

    /**
     * 완료 상태
     * TODO: 미완료 / DONE: 완료
     */
    private String status;

    /**
     * 우선순위
     * HIGH / MEDIUM / LOW
     */
    private String priority;

    /**
     * 생성 출처
     * MANUAL / AI_SUGGESTED / ROUTINE
     */
    private String sourceType;

    /** 완료 처리 시각 (미완료이면 null) */
    private LocalDateTime completedAt;

    /** 생성 시각 */
    private LocalDateTime createdAt;

    /** 수정 시각 */
    private LocalDateTime updatedAt;

    /**
     * Todo 엔티티로부터 응답 DTO 생성
     *
     * @param todo Todo 엔티티
     * @return TodoResponse
     */
    public static TodoResponse from(Todo todo) {
        return TodoResponse.builder()
                .todoId(todo.getTodoId())
                .userId(todo.getUserId())
                .todoDate(todo.getTodoDate())
                .title(todo.getTitle())
                .content(todo.getContent())
                .status(todo.getStatus())
                .priority(todo.getPriority())
                .sourceType(todo.getSourceType())
                .completedAt(todo.getCompletedAt())
                .createdAt(todo.getCreatedAt())
                .updatedAt(todo.getUpdatedAt())
                .build();
    }
}