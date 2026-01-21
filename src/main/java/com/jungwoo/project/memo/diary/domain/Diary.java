package com.jungwoo.project.memo.diary.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Diary {

    private Long diaryId;
    private Long userId;
    private LocalDate writtenDate;
    private String title;
    private String content;
    private String mood;
    private String visibility;
    private String weather;
    private Boolean favorite;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
