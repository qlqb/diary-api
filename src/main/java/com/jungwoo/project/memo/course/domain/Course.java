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

    /** 판 정보(개정 4판 등). 확인한 값만. */
    private String textbookEdition;

    /**
     * USER(사용자가 적거나 고침) / MATERIAL(자료에서 찾은 값을 사용자가 적용) / WEB(웹에서 찾은 판을 사용자가 정했거나
     * 검토한 목차 변경과 함께 적용) / null(모름·예전 값).
     */
    private String textbookInfoSource;

    private Long textbookInfoMaterialId;

    private java.time.LocalDateTime textbookInfoUpdatedAt;

    /** 교재 칸이 바뀔 때마다 1 오른다. 화면은 본 판을 함께 보내고, 다르면 409(조용히 덮지 않는다). */
    private Integer textbookVersion;

    /** WEB일 때 그 판의 웹 리비전(textbook_web_revisions). */
    private Long textbookWebRevisionId;

    /** 사용자가 "이 교재의 목차"로 이은 업로드 자료와 그때의 파일 해시·교재 식별 키. 교재 식별이 바뀌면 풀린다. */
    private Long textbookTocMaterialId;

    private String textbookTocFileHash;

    private String textbookTocBookKey;

    /** false면 교재 단서를 외부(웹 검색·서점 페이지)로 보내지 않는다. */
    private Boolean textbookWebLookupEnabled;

    private CourseStatus status;

    /** 학습 구조(course_topics)를 바꾸는 쓰기마다 1 오른다. 변경안 적용의 낙관적 잠금 기준. */
    private Long topicTreeVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
