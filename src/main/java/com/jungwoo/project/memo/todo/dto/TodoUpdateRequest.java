package com.jungwoo.project.memo.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Todo 수정 요청 DTO
 *
 * 모든 필드는 선택 사항. null 인 필드는 수정하지 않는다.
 * sourceType, routineId, status 는 이 DTO 에서 변경 불가.
 * status 변경은 /complete, /uncomplete 전용 API 를 사용한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoUpdateRequest {

    /** 변경할 날짜 (선택) */
    private LocalDate todoDate;

    /** 변경할 제목 (선택) */
    private String title;

    /** 변경할 메모 (선택) */
    private String content;

    /**
     * 변경할 우선순위 (선택)
     * HIGH / MEDIUM / LOW
     */
    private String priority;
}