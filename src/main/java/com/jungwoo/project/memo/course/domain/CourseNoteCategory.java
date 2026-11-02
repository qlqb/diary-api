package com.jungwoo.project.memo.course.domain;

/**
 * 학습 topic이 아닌, 과목에 관한 사실 정보의 성격.
 *
 * COURSE_INFO: 과목 설명/담당교수/연락처/수업 운영 정보/사용 도구/수업 지원 안내 등.
 * ASSESSMENT: 중간/기말고사, 과제, 평가 비율, 주차별 일정처럼 평가·운영 일정에 관한 사실.
 */
public enum CourseNoteCategory {
    COURSE_INFO,
    ASSESSMENT
}
