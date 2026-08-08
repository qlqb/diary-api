package com.jungwoo.project.memo.course.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 만드는 과목. courses 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 교재 필드들은 Material Agent가 강의계획서를 분석해서 채우기 전까지는 전부 null이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Course {

    private Long courseId;

    private Long userId;

    private String title;

    private String textbookTitle;

    private String textbookAuthor;

    private String textbookPublisher;

    private String textbookIsbn;

    private CourseStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
