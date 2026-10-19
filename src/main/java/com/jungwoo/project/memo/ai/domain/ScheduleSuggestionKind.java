package com.jungwoo.project.memo.ai.domain;

/**
 * AI가 대화에서 뽑은 일정 후보의 종류.
 *
 * <p>이 둘만 있다. 사용자가 직접 수행하고 완료하는 일(EXECUTION)은 여기 오지 않는다 —
 * 그건 기존 Proposal 경로다. 여기는 "시간을 차지하는 현실"만 담는다.
 */
public enum ScheduleSuggestionKind {

    /** 한 번만 발생하는 일정. 승인하면 one_off_commitments로 간다. */
    COMMITMENT,

    /** 반복해서 발생하는 일정. 승인하면 routines로 간다. */
    ROUTINE
}
