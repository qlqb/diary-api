package com.jungwoo.project.memo.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;

/**
 * 모델이 대화에서 뽑은 일정 사실 후보 하나(구조화 출력).
 *
 * <p>payload를 JsonNode로 받는다. kind에 따라 모양이 다르고(COMMITMENT는 startAt/endAt,
 * ROUTINE은 daysOfWeek/effectiveFrom), 두 모양을 다 가진 범용 레코드를 만들면 "이 필드는
 * 이 kind에서만 유효"라는 규칙이 타입 밖으로 나가 주석으로만 남는다.
 *
 * <p>검증은 승인 시점이 아니라 저장 시점에도 한다 — ScheduleSuggestionService가 payload를
 * 실제 도메인 요청(CommitmentCreateRequest / RoutineSaveRequest)으로 읽어 본 뒤에만
 * PROPOSED로 남긴다. 읽을 수 없는 후보를 카드로 띄우면 사용자가 [적용]을 눌러야만 그게
 * 못 쓰는 값이었다는 것을 알게 된다.
 */
public record ScheduleSuggestion(
        ScheduleSuggestionKind kind,
        JsonNode payload
) {
}
