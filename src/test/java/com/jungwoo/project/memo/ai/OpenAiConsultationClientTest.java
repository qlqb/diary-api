package com.jungwoo.project.memo.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM_PROMPT는 실제 OpenAI 응답 없이는 모델의 의미 판단 자체를 증명할 수 없다 — 여기서는
 * "계획 결과를 크게 바꾸는 핵심 정보가 부족하면 OFFER_PROPOSAL보다 ASK_CLARIFICATION을
 * 우선한다"는 판단 기준 문구가 실제로 프롬프트에 포함돼 모델에 전달되는지만 계약 수준에서
 * 확인한다(docs/product/09-ai-consultation-regression-cases.md CASE-001 관련).
 *
 * 특정 문장 하드코딩이 아니라 일반 판단 기준을 추가한 것이므로, 여기서도 정확한 문구가
 * 아니라 핵심 개념(핵심 정보 vs 영향이 작은 정보, ASK 우선, 이미 아는 정보 재질문 금지)의
 * 존재만 검증한다.
 */
class OpenAiConsultationClientTest {

    @Test
    void systemPrompt_definesCoreInformationJudgmentRule_forAskBeforeOffer() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("ASK_CLARIFICATION");
        assertThat(prompt).contains("OFFER_PROPOSAL");
        assertThat(prompt).contains("핵심 정보");
        // 정보가 하나라도 없다고 무조건 ASK하는 설문이 되지 않도록, "영향이 작은" 정보는
        // 보수적으로 추정할 수 있다는 반대 방향 기준도 함께 있어야 한다.
        assertThat(prompt).contains("보수적으로 추정");
    }

    @Test
    void systemPrompt_doesNotAskAboutInformationAlreadyKnown() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("이미 [최근 대화]나 [장기 컨텍스트]에 있는 정보는 같은");
    }

    @Test
    void systemPrompt_asksNaturally_notAsBureaucraticForm() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        // 항목 나열식 설문 문구를 피하라는 지시 자체가 프롬프트에 있어야 한다.
        assertThat(prompt).contains("설문");
    }
}
