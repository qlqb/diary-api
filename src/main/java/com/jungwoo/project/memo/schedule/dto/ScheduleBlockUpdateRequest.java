package com.jungwoo.project.memo.schedule.dto;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시간 블록 전체 수정 요청 DTO (PUT)
 *
 * PUT은 리소스 전체 교체. 날짜/제목/블록 유형은 필수.
 * 시간은 blockType에 따라 서비스에서 검증한다.
 * null 허용 필드(todoId, memo)는 null로 보내면 null로 덮어써진다.
 * originType은 수정 불가.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockUpdateRequest {

    /** 연결할 Todo ID (null 허용 — null이면 연결 해제) */
    private Long todoId;

    /** 날짜 (필수) */
    @NotNull(message = "날짜는 필수입니다")
    private LocalDate blockDate;

    /** 제목 (필수) */
    @NotBlank(message = "제목은 필수입니다")
    private String title;

    /** 블록 유형 (필수) */
    @NotNull(message = "블록 유형은 필수입니다")
    private ScheduleBlockType blockType;

    /** 시작 시각 (TIME_FIXED일 때 필수) */
    private LocalDateTime startTime;

    /** 종료 시각 (TIME_FIXED일 때 필수) */
    private LocalDateTime endTime;

    /** 메모 (null 허용 — null이면 null로 덮어써진다) */
    private String memo;
}
