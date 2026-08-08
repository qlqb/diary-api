package com.jungwoo.project.memo.course.dto;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CourseResponse {

    private Long courseId;
    private String title;
    private String textbookTitle;
    private String textbookAuthor;
    private String textbookPublisher;
    private String textbookIsbn;
    private CourseStatus status;
    private int topicCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CourseResponse of(Course course, int topicCount) {
        return CourseResponse.builder()
                .courseId(course.getCourseId())
                .title(course.getTitle())
                .textbookTitle(course.getTextbookTitle())
                .textbookAuthor(course.getTextbookAuthor())
                .textbookPublisher(course.getTextbookPublisher())
                .textbookIsbn(course.getTextbookIsbn())
                .status(course.getStatus())
                .topicCount(topicCount)
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }
}
