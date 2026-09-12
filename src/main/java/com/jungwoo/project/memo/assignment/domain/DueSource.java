package com.jungwoo.project.memo.assignment.domain;

/** course_assignments.due_source — 마감이 어디서 왔는가. */
public enum DueSource {
    /** 원문에 명시된 날짜. */
    SOURCE,
    /** 상대 표현("다음 수업까지")을 서버가 해석한 추정. 확정 제약으로 쓰지 않는다. */
    ESTIMATED,
    /** 사용자가 입력·확정했다. */
    USER
}
