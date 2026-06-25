package com.jungwoo.project.memo.diary.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryRevision {

    private Long revisionId;
    private Long diaryId;

    private LocalDate writtenDate;

    private String title;
    private String content;
    private String mood;
    private String visibility;
    private String weather;

    private Boolean isFavorite;

    private LocalDateTime editedAt;
}