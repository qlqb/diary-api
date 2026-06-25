package com.jungwoo.project.memo.diary.dto;

import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 단일 수정 이력 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryRevisionResponse {

    /** 수정 이력 ID */
    private Long revisionId;

    /** 일기 ID */
    private Long diaryId;

    /** 일기 작성 날짜 */
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
    private Boolean favorite;

    /** 수정 이력 저장 시간 */
    private LocalDateTime editedAt;

    public static DiaryRevisionResponse from(DiaryRevision revision) {
        return DiaryRevisionResponse.builder()
                .revisionId(revision.getRevisionId())
                .diaryId(revision.getDiaryId())
                .writtenDate(revision.getWrittenDate())
                .title(revision.getTitle())
                .content(revision.getContent())
                .mood(revision.getMood())
                .visibility(revision.getVisibility())
                .weather(revision.getWeather())
                .favorite(revision.getIsFavorite())
                .editedAt(revision.getEditedAt())
                .build();
    }
}