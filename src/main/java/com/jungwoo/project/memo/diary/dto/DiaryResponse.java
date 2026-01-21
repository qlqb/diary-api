package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
public class DiaryResponse {

    private Long diaryId;
    private LocalDate writtenDate;
    private String title;
    private String content;
    private String mood;
    private String visibility;
    private Boolean favorite;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
