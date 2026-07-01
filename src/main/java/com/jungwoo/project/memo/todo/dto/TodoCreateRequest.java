package com.jungwoo.project.memo.todo.dto;

import com.jungwoo.project.memo.todo.domain.TodoPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoCreateRequest {

    @NotNull(message = "날짜는 필수입니다")
    private LocalDate todoDate;

    @NotBlank(message = "제목은 필수입니다")
    private String title;

    private String content;

    // null로 오면 Service에서 MEDIUM으로 처리
    private TodoPriority priority;
}
