package com.jungwoo.project.memo.course.textbook;

/**
 * 과목의 교재 근거가 바뀌었다(교재 칸·목차 연결·연결 자료). 커밋 뒤에 교재 조회를 새 근거로 맞춘다.
 *
 * @param textbookVersion 바뀐 뒤의 판. 자료 연결 변화처럼 판이 오르지 않은 경우는 null
 */
public record TextbookChangedEvent(Long userId, Long courseId, Integer textbookVersion) {
}
