package com.jungwoo.project.memo.assignment.domain;

/** course_assignments.due_kind. 미확인과 없음은 다르다. */
public enum DueKind {
    /** 아직 모른다. */
    UNKNOWN,
    /** 마감이 없다고 사용자가 정했다. */
    NONE,
    /** 날짜까지. 내부 배타 경계는 다음날 00:00이지만 화면에는 이 날짜로 보인다. */
    DATE,
    /** 시각까지. 사용자 시간대 기준 LocalDateTime. */
    DATETIME
}
