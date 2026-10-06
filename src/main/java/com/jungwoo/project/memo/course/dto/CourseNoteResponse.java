package com.jungwoo.project.memo.course.dto;

import com.jungwoo.project.memo.course.domain.CourseNote;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CourseNoteResponse {

    private Long noteId;
    private String category;
    private String label;
    private String detail;
    private LocalDateTime createdAt;

    public static CourseNoteResponse of(CourseNote note) {
        return CourseNoteResponse.builder()
                .noteId(note.getNoteId())
                .category(note.getCategory().name())
                .label(note.getLabel())
                .detail(note.getDetail())
                .createdAt(note.getCreatedAt())
                .build();
    }
}
