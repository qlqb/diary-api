package com.jungwoo.project.memo.todo.domain;

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
public class Todo {

    private Long todoId;
    private Long userId;
    private LocalDate todoDate;
    private String title;
    private String content;

    // enum 타입. MyBatis가 name()으로 DB 문자열과 자동 변환.
    private TodoStatus status;
    private TodoPriority priority;
    private TodoOriginType originType;

    // AI/루틴 Todo를 사용자가 수정하면 true. MANUAL은 항상 false.
    private Boolean modifiedAfterCreation;

    // nullable. Routine 기능 구현 후 FK 연결 예정.
    private Long routineId;

    private LocalDateTime completedAt;
    private Boolean isDeleted;

    // DB DEFAULT CURRENT_TIMESTAMP / ON UPDATE로 자동처리. Java에서 주입하지 않음.
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
