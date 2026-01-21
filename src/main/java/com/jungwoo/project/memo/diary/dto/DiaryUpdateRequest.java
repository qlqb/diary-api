package com.jungwoo.project.memo.diary.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DiaryUpdateRequest {

    private String title;
    private String content;
    private String mood;
    private String visibility;
    private String weather;

}
