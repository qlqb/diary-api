package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 상담 모델이 낸 계획 합의 변경 <b>제안</b>. 서버(PlanBriefService)가 검증해 적용한다 — 모델은 합의를 직접 쓰지 않는다.
 *
 * <ul>
 *   <li>ADD: 새 문장. speaker=USER면 사용자가 말한 것(즉시 유효), ASSISTANT면 AI 제안(후보).</li>
 *   <li>ACCEPT: 사용자가 기존 AI 제안을 받아들였다("좋아", "그대로").</li>
 *   <li>REJECT: 사용자가 기존 AI 제안을 거절했다.</li>
 *   <li>UPDATE: 사용자가 기존 문장을 고쳤다(text 필수). 최신 수정판이 우선한다.</li>
 *   <li>REMOVE: 더 이상 유효하지 않다(사용자가 철회).</li>
 * </ul>
 *
 * @param id 기존 항목 번호(ACCEPT/REJECT/UPDATE/REMOVE). ADD는 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanBriefOp(
        String op,
        Integer id,
        String kind,
        String text,
        String speaker,
        String scope,
        Long topicId,
        Long courseId,
        Long executionItemId
) {
}
