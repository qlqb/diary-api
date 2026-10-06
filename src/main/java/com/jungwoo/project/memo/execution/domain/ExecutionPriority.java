package com.jungwoo.project.memo.execution.domain;

/**
 * 사용자가 확인한 중요도. execution_items.priority 체크 제약과 1:1 대응한다.
 *
 * AI의 임시 추천 점수와 추천 이유는 여기 저장하지 않는다.
 * Proposal 안에 후보로 두고, 사용자가 적용한 경우에만 이 값을 쓴다.
 */
public enum ExecutionPriority {
    MUST,
    SHOULD,
    OPTIONAL
}
