package com.jungwoo.project.memo.diary.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class DiaryCreateRequest {

    private LocalDate writtenDate;
    private String title;
    private String content;
    private String mood;
    private String visibility;
    private String weather;


}
