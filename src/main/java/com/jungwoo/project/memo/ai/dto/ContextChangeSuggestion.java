package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.ContextChangeOperation;

/**
 * 모델이 만든 장기 컨텍스트 변경 후보 하나(구조화 JSON의 contextChanges 배열 원소).
 *
 * decision(CHAT/ASK_CLARIFICATION/OFFER_PROPOSAL/PROPOSAL_READY)과 독립적인 sidecar다 —
 * 이 레코드가 있다고 responseType이 바뀌지 않는다. 서버는 이 값을 그대로 신뢰하지 않고
 * ContextChangeSuggestionService가 연산별 계약(targetContextId/content 필수 여부)과
 * 소유권을 다시 검증한다.
 *
 * targetContextId는 [장기 컨텍스트] 프롬프트 블록에 함께 전달한 context_id를 그대로
 * 가리킨다 — 모델이 새 id를 지어내지 않는다.
 */
public record ContextChangeSuggestion(
        ContextChangeOperation operation,
        Long targetContextId,
        String content,
        String reason
) {
}
