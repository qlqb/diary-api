package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiResponseType;

import java.util.List;

/**
 * 모델 출력의 구분자(&lt;&lt;&lt;AI_STRUCTURED&gt;&gt;&gt;) 뒤 JSON을 파싱한 결과.
 * reply(구분자 앞 텍스트)는 스트리밍 중에 이미 별도로 처리하므로 여기 포함하지 않는다.
 *
 * unavailableWindows는 PROPOSAL에서만 값을 가질 수 있는 대화 차원의 제약이다(개별 항목이
 * 아니라 이번 계획 전체에 적용된다) — CHAT/OFFER에서는 항상 빈 배열이거나 null이다.
 */
public record AiTurnStructured(
        AiResponseType responseType,
        List<ProposalItem> proposalItems,
        OfferAction offerAction,
        List<UnavailableWindowSpec> unavailableWindows
) {
}
