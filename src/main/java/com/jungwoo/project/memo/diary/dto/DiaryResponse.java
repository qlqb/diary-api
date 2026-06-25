package com.jungwoo.project.memo.diary.dto;

import com.jungwoo.project.memo.diary.domain.Diary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일기 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiaryResponse {

    /** 일기 ID */
    private Long diaryId;

    /** 작성자 ID */
    private Long userId;

    /** 작성 날짜 */
    private LocalDate writtenDate;

    /** 제목 */
    private String title;

    /** 내용 */
    private String content;

    /** 기분 */
    private String mood;

    /** 공개 범위 */
    private String visibility;

    /** 날씨 */
    private String weather;

    /** 즐겨찾기 여부 */
    private Boolean isFavorite;

    /** 생성 시간 */
    private LocalDateTime createdAt;

    /** 수정 시간 */
    private LocalDateTime updatedAt;

    public static DiaryResponse from(Diary diary) {
        return DiaryResponse.builder()
                .diaryId(diary.getDiaryId())
                .userId(diary.getUserId())
                .writtenDate(diary.getWrittenDate())
                .title(diary.getTitle())
                .content(diary.getContent())
                .mood(diary.getMood())
                .visibility(diary.getVisibility())
                .weather(diary.getWeather())
                .isFavorite(diary.getIsFavorite())
                .createdAt(diary.getCreatedAt())
                .updatedAt(diary.getUpdatedAt())
                .build();
    }
}