package com.jungwoo.project.memo.course.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * course_topics(학습 내용)와 구분되는, "학습 대상이 아닌" 과목 사실 정보. course_notes 테이블과
 * 1:1 대응. course_topics처럼 Material Agent 분석의 apply()를 통해서만 생성된다 — 직접 CRUD는
 * 없다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseNote {

    private Long noteId;

    private Long userId;

    private Long courseId;

    private CourseNoteCategory category;

    private String label;

    private String detail;

    private Long sourceMaterialId;

    private LocalDateTime createdAt;
}
