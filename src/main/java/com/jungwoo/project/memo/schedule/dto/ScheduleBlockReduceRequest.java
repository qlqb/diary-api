package com.jungwoo.project.memo.schedule.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 작게 줄이기 요청.
 *
 * 신규 클라이언트는 reducedTitle을 사용한다.
 * 기존 afterTitle 요청은 하위 호환을 위해 임시로 허용한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockReduceRequest {

    @JsonAlias("afterTitle")
    @NotBlank(message = "줄인 뒤 제목은 필수입니다")
    @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다")
    private String reducedTitle;

    private ScheduleBlockReduceTimeMode timeMode;

    private String memo;

    private ScheduleBlockType blockType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
