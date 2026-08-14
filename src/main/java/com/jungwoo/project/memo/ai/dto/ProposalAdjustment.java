package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.ProposalOperation;

import java.time.LocalDate;

/**
 * 모델이 만든 "기존 실행 조각을 이렇게 바꾸자"는 후보 하나.
 *
 * executionItemId는 프롬프트의 [오늘 실행 상태]/[이번 주 일정]에 #번호로 실려 나간 값을 그대로
 * 참조한 것이어야 한다 — 서버가 소유권과 상태를 다시 확인하며, 존재하지 않거나 이미 끝난
 * 항목을 가리키면 그 후보만 조용히 버린다(턴 전체를 실패시키지 않는다).
 *
 * 이 후보 자체는 아무것도 바꾸지 않는다. 사용자가 화면에서 보고 명시적으로 적용해야만
 * 실제 execution_items가 바뀐다.
 */
public record ProposalAdjustment(
        Long executionItemId,
        ProposalOperation operation,
        /** REDUCE: 줄일 분량. */
        Integer expectedMinutes,
        /** REDUCE: 더 작게 바꾼 제목(선택). */
        String title,
        /** MOVE: 옮길 날짜. */
        LocalDate toDate,
        String reason
) {
}
