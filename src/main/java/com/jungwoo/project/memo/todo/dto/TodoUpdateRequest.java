package com.jungwoo.project.memo.todo.dto;

import com.jungwoo.project.memo.todo.domain.TodoPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoUpdateRequest {

    // 모든 필드 선택. null인 필드는 수정하지 않음.
    private LocalDate todoDate;
    private String title;
    private String content;
    private TodoPriority priority;
}
