package com.jungwoo.project.memo.schedule.dto;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시간 블록 부분 수정 요청 DTO (PATCH)
 *
 * PATCH는 보낸 필드만 수정. null인 필드는 기존값 유지.
 * originType은 수정 불가.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockPatchRequest {

    /** 변경할 Todo ID (선택) */
    private Long todoId;

    /** 변경할 날짜 (선택) */
    private LocalDate blockDate;

    /** 변경할 제목 (선택) */
    private String title;

    /** 변경할 블록 유형 (선택) */
    private ScheduleBlockType blockType;

    /** 변경할 시작 시각 (선택) */
    private LocalDateTime startTime;

    /** 변경할 종료 시각 (선택) */
    private LocalDateTime endTime;

    /** 변경할 메모 (선택) */
    private String memo;
}