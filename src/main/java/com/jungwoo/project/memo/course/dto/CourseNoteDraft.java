package com.jungwoo.project.memo.course.dto;

/**
 * Material Agent 분석 결과(또는 그 수정본)에서 확정 전 단계의 과목 정보/평가 정보 한 줄.
 * CourseNoteService.saveAll()의 입력 전용 — course_notes 확정 삽입에만 쓰인다.
 * learning.dto.TopicDraft와 같은 역할을 course_notes에 대해 한다 — material 패키지가 이
 * 중립 타입을 통해서만 course 패키지에 데이터를 넘기게 해 상위 패키지(material)가 하위 패키지
 * (course)에 의존을 만들지 않는다.
 */
public record CourseNoteDraft(String category, String label, String detail) {
}
