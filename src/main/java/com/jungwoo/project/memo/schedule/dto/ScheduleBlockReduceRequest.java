package com.jungwoo.project.memo.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 작게 줄이기 요청.
 * 예: "영어 30분" → "영어 단어 5개"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockReduceRequest {

    @NotBlank(message = "줄인 후 제목은 필수입니다")
    @Size(max = 255, message = "제목은 255자를 넘을 수 없습니다")
    private String afterTitle;

    private String memo;
}
