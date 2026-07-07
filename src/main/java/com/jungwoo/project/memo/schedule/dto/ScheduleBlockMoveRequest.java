package com.jungwoo.project.memo.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 이동(내일로) 요청.
 * todoId는 받지 않는다 — 서버가 블록에서 복사한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockMoveRequest {

    @NotNull(message = "이동할 날짜는 필수입니다")
    private LocalDate toDate;

    private String memo;
}
