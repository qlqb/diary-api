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

    /*
     * "고정으로 빼야 할 일정(알바·수업·약속)이 있나요"라고 되물은 적이 있다. 셋 다 앱이
     * 이미 아는 값이고, 지금은 화면 상태 블록에 실려 나간다 — 되물으면 사용자에게 앱이
     * 아는 것을 다시 시키는 셈이다(docs/product-thesis.md 현실층 갱신 원칙 ①).
     *
     * 대화 이력만 언급하던 옛 문구로는 이 경우를 막지 못했다. 규칙이 화면 상태 블록까지
     * 덮는지, 그리고 되묻지 말아야 할 블록 이름이 실제로 적혀 있는지 함께 고정한다.
     */
    @Test
    void systemPrompt_doesNotAskAboutInformationAlreadyKnown() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("화면 상태 블록에 이미 있는 것은 절대 되묻지 않는다");
        assertThat(prompt).contains("[최근 대화]");
        assertThat(prompt).contains("[장기 컨텍스트]");
        // 이번에 되물었던 바로 그 값들이 이름으로 적혀 있어야 한다.
        assertThat(prompt).contains("[이번 주 일정]");
        assertThat(prompt).contains("[남는 시간(추정)]");
        assertThat(prompt).contains("고정 일정(수업·알바·약속)");
        // 블록이 비어 있는 것을 "모른다"로 읽고 되묻는 것도 막아야 한다.
        assertThat(prompt).contains("비어 있다면 그건 \"없다\"는 뜻이지");
    }

    @Test
    void systemPrompt_asksNaturally_notAsBureaucraticForm() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        // 항목 나열식 설문 문구를 피하라는 지시 자체가 프롬프트에 있어야 한다.
        assertThat(prompt).contains("설문");
    }

    /**
     * "늦게 일어나서 오전 계획을 다 못했어" 같은 입력에서 지켜야 하는 것: 실패로 규정하지 않고,
     * 이미 아는 것을 다시 묻지 않고, AUTO에서 곧바로 초안을 만들지 않고, 오늘 범위를 넘지 않는다.
     * 실제 모델이 그렇게 판단하는지는 Mockito로 증명할 수 없으므로 지시가 전달되는지만 확인한다.
     */
    @Test
    void systemPrompt_treatsBrokenPlanAsAdjustment_notFailure() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("예정 시간 지남");
        assertThat(prompt).contains("실패가 아니라");
        assertThat(prompt).contains("남은 오늘을 다시 잡아볼까?");
        // 오늘 계획이 틀어졌다는 말을 주간/월간/새 목표로 넓히지 않는다.
        assertThat(prompt).contains("주간 계획·월간 계획·새 목표");
    }

    @Test
    void systemPrompt_allowsSameDayTimeMove_forOverdueItems() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        // "오늘 뒤로"(같은 날 시각만 이동)도 MOVE 하나로 표현한다 — 별도 operation을 만들지 않는다.
        assertThat(prompt).contains("같은 날 안에서 시각만 뒤로 미는");
        assertThat(prompt).contains("\"startTime\"");
    }

    /*
     * [이번 주 일정]에 수업·알바를 실었더니 모델이 그것을 "등록해야 할 일정"으로 읽고
     * COMMITMENT 후보로 다시 냈다. 적용하면 routines/one_off_commitments에 중복이 생긴다.
     * "되묻지 마라"는 있었지만 "다시 내지 마라"가 없었다.
     */
    @Test
    void systemPrompt_forbidsReproposingSchedulesAlreadyRegistered() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("이미 앱에 등록된 일정을 후보로 다시 내지 않는다");
        assertThat(prompt).contains("[이미 등록됨 · 반복 일정]");
        assertThat(prompt).contains("사용자가 이번 대화에서 새로 말한 일정뿐이다");
    }

    /*
     * 계획 경로(PlanDraftService)에는 "개강일로 몇 주차인지 계산하고 이번 주·다음 주 진도에
     * 집중하라"가 있는데 대화 경로에는 없었다("주차"·"개강" 검색 0건). 그 결과 이 강의계획서에
     * 없는 "핵심 3개(배열·리스트·스택)"가 나왔다 — 2주차 진도는 ADT·Big-O였다.
     */
    @Test
    void systemPrompt_tellsHowToUseWeekNumbers_andForbidsInventingTopics() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("몇 주차인지 계산");
        assertThat(prompt).contains("이번 주와 다음 주 진도에 집중");
        assertThat(prompt).contains("한참 뒤 주차 내용을 미리 당겨오지 않는다");
        // 지어내기 금지는 반드시 있어야 한다.
        assertThat(prompt).contains("학습 항목에 없는 것을 지어내지 않는다");
        // 줄바꿈을 타지 않는 조각으로 본다 — 원문은 "교재의 장 / 번호나 쪽수처럼"으로 접힌다.
        assertThat(prompt).contains("번호나 쪽수처럼 거기 없는 값은 만들지 않는다");
        assertThat(prompt).contains("상상해 넣는 것도 지어내는 것이다");
    }
}
