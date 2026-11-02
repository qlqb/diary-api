package com.jungwoo.project.memo.ai.domain;

/**
 * AI 변경안 헤더의 생애주기. ai_proposals.status 체크 제약과 1:1 대응한다.
 *
 * PARTIALLY_APPLIED는 존재하지 않는다 — 이번 범위는 묶음 전체 원자 적용만 지원한다.
 */
public enum AiProposalStatus {
    PROPOSED,
    APPLIED,
    MODIFIED_APPLIED,
    DISMISSED,
    EXPIRED
}
