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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockCreateRequest {

    private Long todoId;

    @NotNull(message = "날짜는 필수입니다")
    private LocalDate blockDate;

    @NotBlank(message = "제목은 필수입니다")
    private String title;

    @NotNull(message = "블록 유형은 필수입니다")
    private ScheduleBlockType blockType;

    @NotNull(message = "시작 시각은 필수입니다")
    private LocalDateTime startTime;

    @NotNull(message = "종료 시각은 필수입니다")
    private LocalDateTime endTime;

    private String memo;
}
