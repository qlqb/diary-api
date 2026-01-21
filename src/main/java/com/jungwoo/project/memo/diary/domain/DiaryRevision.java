package com.jungwoo.project.memo.diary.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class DiaryRevision {

    private Long revisionId;
    private Long diaryId;
    private String title;
    private String content;
    private String mood;
    private LocalDateTime createdAt;

    public DiaryRevision() {
    }

    public DiaryRevision(Long revisionId, Long diaryId, String title, String content, LocalDateTime now) {
        this.revisionId=revisionId;
        this.diaryId=diaryId;
        this.title=title;
        this.content=content;
        this.createdAt=now;
    }

}
