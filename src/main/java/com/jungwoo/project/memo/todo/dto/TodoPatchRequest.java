package com.jungwoo.project.memo.todo.dto;

import com.jungwoo.project.memo.todo.domain.TodoPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Todo 부분 수정 요청 DTO (PATCH)
 *
 * PATCH는 보낸 필드만 수정. null인 필드는 기존값 유지.
 * 최소 하나의 필드만 보내도 된다.
 *
 * status 변경은 /done, /todo 전용 엔드포인트를 사용한다.
 * originType, routineId는 수정 불가.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoPatchRequest {

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
    private TodoPriority priority;
}