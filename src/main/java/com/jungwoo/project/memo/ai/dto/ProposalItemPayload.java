package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ai_proposal_items.original_payload / edited_payload에 저장되는 JSON의 자바 표현.
 * targetDate는 서버가 요청의 targetDate로 확정해 넣는다 — 모델 출력에 맡기지 않는다.
 *
 * placementType이 DATE_ONLY면 scheduledStartAt/scheduledEndAt은 항상 null이고,
 * TIME_FIXED면 둘 다 not null이며 그 날짜가 targetDate와 같아야 한다 (execution_items의
 * 배치 무결성 조건과 동일).
 */
public record ProposalItemPayload(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        LocalDate targetDate,
        PlacementType placementType,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt
) {
}
