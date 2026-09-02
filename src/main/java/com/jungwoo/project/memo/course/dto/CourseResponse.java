package com.jungwoo.project.memo.course.dto;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 프로젝트 응답.
 *
 * 목록 화면이 프로젝트 하나당 topic 트리를 따로 조회하지 않아도 되도록, 카드에 필요한 요약
 * (학습 구조 수 / 학습 완료 수 / 지금 진행 중인 주제)을 여기서 함께 내려준다.
 *
 * 자료 수는 내려주지 않는다 — 자료가 프로젝트 소유에서 연결로 바뀌면서 "개수"만으로는
 * 알 수 있는 게 없어졌고, 카드에서도 그 칩을 뺐다.
 */
@Getter
@Builder
public class CourseResponse {

    private Long courseId;
    private String title;
    private String groupLabel;
    private String textbookTitle;
    private String textbookAuthor;
    private String textbookPublisher;
    private String textbookIsbn;
    private CourseStatus status;
    private int topicCount;
    private int learnedTopicCount;
    /** 진행 중(IN_PROGRESS)인 주제 하나. 없으면 null — 빈 프로젝트도 정상 상태다. */
    private String currentTopicTitle;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CourseResponse of(Course course, CourseSummaryCounts counts) {
        return CourseResponse.builder()
                .courseId(course.getCourseId())
                .title(course.getTitle())
                .groupLabel(course.getGroupLabel())
                .textbookTitle(course.getTextbookTitle())
                .textbookAuthor(course.getTextbookAuthor())
                .textbookPublisher(course.getTextbookPublisher())
                .textbookIsbn(course.getTextbookIsbn())
                .status(course.getStatus())
                .topicCount(counts != null ? counts.getTopicCount() : 0)
                .learnedTopicCount(counts != null ? counts.getLearnedTopicCount() : 0)
                .currentTopicTitle(counts != null ? counts.getCurrentTopicTitle() : null)
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }
}
