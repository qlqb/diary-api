package com.jungwoo.project.memo.diary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter

/**
 * 일기 작성 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiaryCreateRequest {

    /** 일기 작성 날짜 (필수) */
    @NotNull(message = "작성 날짜는 필수입니다")
    private LocalDate writtenDate;

    /** 일기 제목 (필수) */
    @NotBlank(message = "제목은 필수입니다")
    private String title;

    /** 일기 내용 (필수) */
    @NotBlank(message = "내용은 필수입니다")
    private String content;

    /** 기분 (happy, neutral, sad, excited 등) */
    private String mood;

    /** 공개 범위 (기본값: PRIVATE) */
    @Builder.Default
    private String visibility = "PRIVATE";

    /** 날씨 정보 (선택) */
    private String weather;
}