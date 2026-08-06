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
 * 배치 무결성 조건과 동일). UNSCHEDULED면 scheduledStartAt/scheduledEndAt이 모두 null이고
 * targetDate는 아직 확정되지 않은 최초 추정값(대개 오늘)일 뿐이며, 7일 범위 배치 미리보기가
 * 실제 날짜를 결정한 뒤 적용 시점에 최종 날짜로 교체된다.
 *
 * earliestStartDate/deadlineDate는 UNSCHEDULED 후보를 Timefold가 배치할 때 참고하는
 * 선택적 힌트다. AI_INFERRED 값이며 사용자가 직접 확정한 사실이 아니므로 HARD 제약으로
 * 승격하지 않는다(마감일만 예외 — 사용자가 명시한 마감은 강한 제약으로 다룬다).
 */
public record ProposalItemPayload(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        LocalDate targetDate,
        PlacementType placementType,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt,
        LocalDate earliestStartDate,
        LocalDate deadlineDate
) {
}
