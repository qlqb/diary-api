package com.jungwoo.project.memo.course.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 카드 요약 수치. CourseMapper가 프로젝트 목록 한 번의 조회로 함께 채운다 —
 * 카드마다 topic 트리를 따로 가져오지 않기 위한 값이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseSummaryCounts {

    private Long courseId;
    private int topicCount;
    private int learnedTopicCount;
    private String currentTopicTitle;
}
