package com.jungwoo.project.memo.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Todo 생성 요청 DTO
 *
 * sourceType 은 클라이언트에서 받지 않는다.
 * 이번 단계에서는 Service 에서 항상 MANUAL 로 고정한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoCreateRequest {

    /** Todo 날짜 (필수) */
    @NotNull(message = "날짜는 필수입니다")
    private LocalDate todoDate;

    /** Todo 제목 (필수) */
    @NotBlank(message = "제목은 필수입니다")
    private String title;

    /** Todo 메모 (선택) */
    private String content;

    /**
     * 우선순위 (선택, 기본값: MEDIUM)
     * HIGH / MEDIUM / LOW
     */
    @Builder.Default
    private String priority = "MEDIUM";
}