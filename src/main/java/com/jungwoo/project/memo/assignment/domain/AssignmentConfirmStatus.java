package com.jungwoo.project.memo.assignment.domain;

/** course_assignments.confirm_status — "과제인가요?"의 답. */
public enum AssignmentConfirmStatus {
    /** 자료에서 제출 단서가 보여 묻는 중. */
    CANDIDATE,
    /** 과제 맞아요. */
    CONFIRMED,
    /** 연습용이에요 — 과제가 아니다. */
    NOT_ASSIGNMENT,
    /** 나중에. 과제 아님으로 저장하지 않는다. 목록에 남고 다시 묻지 않는다. */
    LATER,
    /** 이미 등록된 과제와 같은 것. duplicate_of_assignment_id가 원본이다. */
    DUPLICATE
}
