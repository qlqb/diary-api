package com.jungwoo.project.memo.routine.domain;

/**
 * 그 주만 달라지는 경우는 두 가지다.
 *
 * <p>EXDATE/RDATE 대신 이 하나로 둘 다 표현한다. 강의계획서의 예외는 전부 "이동"이라
 * EXDATE(그날 없음)만으로는 절반밖에 못 쓴다.
 */
public enum RoutineExceptionType {

    /** 그날은 없다. */
    SKIP,

    /** 그날 대신 다른 날에 있다. 보강·대타가 여기 해당한다. */
    MOVED
}
