package com.jungwoo.project.memo.routine.domain;

/**
 * 예외 날짜가 그 루틴에서 성립하지 않는 이유.
 *
 * <p>두 조건은 서로 독립이고, 한 예외가 둘 다 위반할 수 있다 — 요일과 기간을 한 번에 바꾸면
 * 그렇게 된다. 그래서 판정은 "유효한가"(boolean)가 아니라 "무엇이 걸렸는가"(목록)다.
 *
 * <p>문자열이 아니라 enum인 이유는 화면이 이 값으로 문구를 고르기 때문이다. 서버가 문구를
 * 보내면 문구를 다듬는 순간 화면이 깨진다.
 */
public enum RoutineExceptionConflictReason {

    /** 그 날짜의 요일이 이 루틴의 요일 집합에 없다. 그 날에는 발생분이 아예 생기지 않는다. */
    DAY_OF_WEEK_MISMATCH,

    /** effectiveFrom ~ effectiveUntil 밖이다. movedDate는 이 검사를 받지 않는다. */
    OUTSIDE_EFFECTIVE_RANGE
}
