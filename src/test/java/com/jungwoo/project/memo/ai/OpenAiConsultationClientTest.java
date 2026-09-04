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

    /*
     * "11:00~12:00 이동시간으로 반영해둘게요"라고 답했는데 아무것도 저장되지 않았다. 이 앱에서
     * 모델이 직접 저장하는 것은 없다 — 후보 카드를 띄우고 사용자가 승인해야 저장된다. 완료형만
     * 막으면 부족하다. 실제 사고 문구는 미래 약속형("~해둘게요")이었다.
     */
    @Test
    void systemPrompt_forbidsClaimingItSavedAnything_includingFuturePromises() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("네가 직접 저장하는 것은 없다");
        assertThat(prompt).contains("사용자가 화면에서 승인해야 저장된다");
        // 완료형
        assertThat(prompt).contains("\"반영해뒀어요\", \"추가했어요\", \"등록해뒀어요\", \"저장했어요\"처럼 이미 한 것처럼");
        // 미래 약속형 — 이번 사고의 실제 문구다.
        assertThat(prompt).contains("\"반영해둘게요\", \"추가해둘게요\"처럼 네가 곧 저장할 것처럼 말하지도");
        assertThat(prompt).contains("그 카드를 누르지 않으면");
        assertThat(prompt).contains("아무 데도 저장되지 않은 적이 있다");
        // 대신 무엇을 말해야 하는지
        assertThat(prompt).contains("\"이렇게 추가할까요?\", \"이 내용으로 만들까요?\"처럼 승인을 구하는 형태로 말한다");
        // 담을 곳이 없을 때
        assertThat(prompt).contains("후보를 만들지 않는 턴에서는 저장을 약속하지 마라");
        assertThat(prompt).contains("담을 곳이 없으면");
    }

    /*
     * "11시부터 12시까지는 이동시간이야"에 AI가 "반영해둘게요"라고 답했는데 후보 카드가 뜨지
     * 않았다. COMMITMENT 예시가 약속·병원뿐이라 모델이 이동을 "시간을 차지하는 현실 일정"으로
     * 보지 않았고, 다음 계획 생성 턴에서 unavailableWindows로만 냈다 — 그 값은 그 제안 하나에만
     * 붙는 일회성이라 약속으로 저장되지 않았다. 판단 기준을 "해야 할 일인가"가 아니라 "그 시간에
     * 다른 걸 할 수 없는가"로 명시한다.
     */
    @Test
    void systemPrompt_treatsAnythingThatOccupiesTime_asACommitmentCandidate() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("COMMITMENT는 한 번만 발생하며 그 시간에 다른 일을 할 수 없는 것이다");
        assertThat(prompt).contains("약속·병원·면접뿐 아니라 이동·통학·행사·외출도 포함한다");
        assertThat(prompt).contains("완료할 대상이 아니라");
        assertThat(prompt).contains("\"해야 할 일인가\"가 아니라 \"그 시간에 다른");
        assertThat(prompt).contains("걸 할 수 없는가\"다");
        // 실제로 놓친 발화가 예시에 있어야 한다.
        assertThat(prompt).contains("\"11시부터 12시까지는 이동시간이야\"      -> COMMITMENT 후보(제목 \"이동\")");
        assertThat(prompt).contains("학과 행사");
    }

    @Test
    void systemPrompt_keepsTravelTimeSeparate_andDoesNotSettleForUnavailableWindows() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        // 앞뒤 일정과 합치면 "병원 10~12시"가 되어 사실과 달라진다.
        assertThat(prompt).contains("앞뒤 일정과 합치지 말고 별도 COMMITMENT로 낸다");
        assertThat(prompt).contains("\"병원 10~12시\"가");
        // unavailableWindows는 그 제안에만 붙는 일회성이라는 것을 모델도 알아야 한다.
        assertThat(prompt).contains("unavailableWindows로만 처리하고 끝내지 마라");
        assertThat(prompt).contains("이번 계획");
        assertThat(prompt).contains("계산에만 쓰이는 일회성 값이라 다음 대화와 다른 계획에는 남지 않는다");
        // 스키마 쪽 설명도 같은 범위를 말해야 한다 — 원칙만 고치면 모델이 스키마를 따른다.
        assertThat(prompt).contains("약속·병원·면접·이동·통학·행사·외출");
    }

    /*
     * 진입 탭이 아니라 의도가 계획 경로를 정한다. 모델은 목적(PERIOD_PLAN/EXECUTION_CHANGE)을
     * 명시하고, 기간 계획이면 OFFER 단계에서 기간·강도·대상 프로젝트를 채운다. 강도를 모르면
     * 한 번 묻고, 자연어(가볍게/적당히/빡세게)를 세 강도로 읽는다. 항목은 만들지 않는다 — 서버의
     * 계획 생성기가 만든다.
     */
    @Test
    void systemPrompt_routesByProposalPurpose_andAsksIntensityOnce() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("\"proposalPurpose\": \"PERIOD_PLAN\" 또는 \"EXECUTION_CHANGE\" 또는 null");
        assertThat(prompt).contains("\"planIntensity\": \"LIGHT\" 또는 \"NORMAL\" 또는 \"FOCUSED\" 또는 null");
        assertThat(prompt).contains("\"targetCourseIds\"");
        assertThat(prompt).contains("어느 탭에서 말했는지가 아니라 사용자의");
        assertThat(prompt).contains("PERIOD_PLAN(기간 계획)");
        assertThat(prompt).contains("EXECUTION_CHANGE(실행 조정·단건)");
        assertThat(prompt).contains("missingInformation=[\"PLAN_INTENSITY\"]");
        assertThat(prompt).contains("\"조금만·핵심만·가볍게\" → LIGHT");
        assertThat(prompt).contains("\"적당히·균형 있게·알아서\" → NORMAL");
        assertThat(prompt).contains("\"빡세게·가능한 만큼·거의 꽉 채워\" → FOCUSED");
        assertThat(prompt).contains("이미 말했으면 다시 묻지 않는다");
        assertThat(prompt).contains("기간 계획과 기존 항목 조정을 한 번에 섞지 않는다");
        assertThat(prompt).contains("PERIOD_PLAN에서");
        assertThat(prompt).contains("proposalItems를 미리 채우지 않는다");
    }

    /*
     * 대화 경로 항목도 60분에 몰렸다. 여기에는 5~120이라는 범위 말고는 시간에 대한 말이 없었다.
     * 전용 계획 경로와 같은 조각(PlanItemPromptRules.DURATION_PHRASES)을 싣는다. 대화 경로에서
     * "채워야 할 양"으로 읽힐 수 있는 것은 [남는 시간(추정)]이라 그것도 예산이라고 못 박는다.
     */
    @Test
    void systemPrompt_givesTaskSizedDurations_andTreatsRemainingTimeAsABudget() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        PlanItemPromptRules.assertCarriesDurationRules(prompt);
        assertThat(prompt).contains("[남는 시간(추정)]도 예산이지 채워야 할 양이 아니다");
        // 서버 검증 범위 안내는 그대로다.
        assertThat(prompt).contains("\"expectedMinutes\": 5에서 120 사이의 양의 정수");
    }

    /*
     * 2주차인데 "스택/큐/트리"(5~10주차)가 나왔다. 컨텍스트에 그 과목의 학습 항목이 한 줄도
     * 없었고(예산 순서 문제, AiWorkspaceContextBuilder에서 고침), 프롬프트에는 "없으면 넓게
     * 잡으라"고만 있어 모델이 과목명으로 일반 커리큘럼을 채웠다. 컨텍스트 밖 진도 추측을
     * 금지하고, 맞는 항목이 없으면 생략하거나 확인하게 한다.
     */
    @Test
    void systemPrompt_confinesConversationPlansToTheWeeksInContext() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        assertThat(prompt).contains("주차가 표시된 학습 항목이 있으면 그 제목 범위 안에서만 계획한다");
        assertThat(prompt).contains("컨텍스트에 없는 이후 주차 개념을 일반 지식으로 만들어내지 않는다");
        assertThat(prompt).contains("스택·큐·트리 같은 일반적인 커리큘럼을 추측하지 않는다");
        assertThat(prompt).contains("임의의 진도를 만들지 말고 그 과목을 생략하거나");
        assertThat(prompt).contains("사용자에게 확인한다");
    }

    /*
     * 학습 항목 제목을 카드 제목으로 옮긴 수준("교재 진도 복습 및 실습 · 90분")이 나왔다.
     * 무엇을 하고 어디까지 하면 끝인지가 없고, 모델이 "교재의 같은 출처" 같은 근거를 지어냈다.
     * 전용 계획 경로(PlanDraftService)와 같은 규칙을 대화 경로에도 싣는다 — 조각 목록은
     * PlanItemPromptRules 한 곳에 있고 양쪽 테스트가 같은 목록을 본다.
     */
    @Test
    void systemPrompt_requiresConcreteActionsAndCompletionCriteria_andForbidsInventedSourcesAndHousekeeping() {
        String prompt = OpenAiConsultationClient.SYSTEM_PROMPT;

        PlanItemPromptRules.assertCarriesRules(prompt);
        // 대화 경로에서 출처로 삼을 수 있는 것은 화면 블록에 실린 것뿐이다.
        assertThat(prompt).contains("출처는 [프로젝트] 블록에 실린 학습 항목 제목과 그 옆 괄호의 위치만 쓴다");
        // 스키마의 description 설명도 같은 형식을 가리켜야 한다 — 원칙만 있고 스키마가
        // "설명 또는 null"이면 모델은 스키마 쪽을 따른다.
        assertThat(prompt).contains("\"description\": \"실제로 할 행동 1~3개 · 완료: 확인 가능한 완료 기준 (원칙 19)");
    }
}
