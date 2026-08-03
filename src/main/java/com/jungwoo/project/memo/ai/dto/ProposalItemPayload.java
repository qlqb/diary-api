package com.jungwoo.project.memo.ai.dto;

import java.time.LocalDate;

/**
 * ai_proposal_items.original_payload / edited_payload에 저장되는 JSON의 자바 표현.
 * targetDate는 서버가 요청의 targetDate로 확정해 넣는다 — 모델 출력에 맡기지 않는다.
 */
public record ProposalItemPayload(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        LocalDate targetDate
) {
}
