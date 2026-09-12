package com.jungwoo.project.memo.execution.domain;

/**
 * 실행 조각이 어디서 만들어졌는지.
 */
public enum ExecutionOriginType {

    /** 사용자가 직접 입력. */
    MANUAL,

    /** AI 변경안을 사용자가 승인해서 생성. */
    AI_GENERATED,

    /** 반복 루틴에서 자동 생성. */
    ROUTINE_GENERATED,

    /** 시간표·강의계획서 등 외부 자료에서 가져옴. */
    IMPORTED
}
