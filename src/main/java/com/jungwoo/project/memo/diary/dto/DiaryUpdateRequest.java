package com.jungwoo.project.memo.diary.dto;

import lombok.*;

import java.time.LocalDate;

/**
 * 일기 수정 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiaryUpdateRequest {

    /** 작성 날짜 */
    private LocalDate writtenDate;

    /** 일기 제목 */
    private String title;

    /** 일기 내용 */
    private String content;

    /** 기분 */
    private String mood;

    /** 공개 범위 */
    private String visibility;

    /** 날씨 정보 */
    private String weather;

    /** 즐겨찾기 여부 */
    private Boolean isFavorite;
}