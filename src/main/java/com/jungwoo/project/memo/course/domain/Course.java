package com.jungwoo.project.memo.course.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 만드는 프로젝트(= AI와 계속 다루고 싶은 하나의 주제/맥락). courses 테이블과 1:1
 * 대응하는 MyBatis 엔티티.
 *
 * 테이블 이름은 courses 그대로다 — 이 단위가 담는 것(제목·자료·topic·상태)은 그대로이고
 * 바뀐 것은 사용자 경험에서의 의미(과목 관리 대상 -> 대화와 실행이 붙는 작업 공간)뿐이라,
 * 이름만 바꾸는 기계적 리팩터링은 하지 않는다.
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

    /** 사용자가 프로젝트를 묶어 보기 위한 자유 텍스트 분류(학교/자격증/개인 등). 없으면 null. */
    private String groupLabel;

    private String textbookTitle;

    private String textbookAuthor;

    private String textbookPublisher;

    private String textbookIsbn;

    private CourseStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
