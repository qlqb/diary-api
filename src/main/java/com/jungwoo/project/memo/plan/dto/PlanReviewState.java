package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 초안 검토 상태(ai_proposals.review_state_json). 사용자가 검토하며 고친 것 — 제목, 항목 포함/제외, 직접 편집값, 미해결
 * 질문의 답. 실행 데이터가 아니고, 확정할 때 화면이 이 값을 그대로 적용 요청에 싣는다.
 *
 * @param version                 저장마다 +1. 클라이언트는 마지막으로 받은 version을 보내고, 다르면 409 — 늦은 저장이 새 편집을 덮지 않는다
 * @param title                   계획 이름(사용자가 고친 값). null이면 초안의 제안 제목
 * @param excludedProposalItemIds 체크를 푼 제안 항목(새 항목·기존 항목 변경 모두)
 * @param editedItems             직접 편집값(시각·분량 등). 화면이 제공하는 편집 범위 그대로
 * @param answers                 미해결 질문(strategy.openQuestions)에 대한 답. 키는 질문 순번
 * @param savedAt                 서버 저장 시각
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanReviewState(
        Integer version,
        String title,
        List<Long> excludedProposalItemIds,
        List<AiProposalApplyRequest.EditedProposalItem> editedItems,
        Map<String, String> answers,
        LocalDateTime savedAt
) {
}
