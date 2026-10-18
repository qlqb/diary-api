package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.scope.ScopeReport;
import org.junit.jupiter.api.Test;

import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.refOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.selection;
import static org.assertj.core.api.Assertions.assertThat;

/** 요청 범위(§2): 선택보다 먼저 뽑고, 선택·계획 입력에 싣고, 최종 항목과 부분별로 대조한다. */
class RequestScopeFlowTest {

    private static String roles(String... roles) {
        return "[" + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList()) + "]";
    }

    @Test
    void 누적_범위의_앞부분을_빠뜨린_계획은_부분별로_미충족이_남는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        f.course(1, "빅데이터분석");
        f.material(10, "4.판다스실습_S.ipynb", 1L);
        f.material(11, "6.판다스활용_붓꽃데이터_타이타닉S (1).ipynb", 1L);
        f.section(101, 10, "판다스 기초", roles("CONCEPT"), 1, "원문-판다스");
        f.section(111, 11, "붓꽃 데이터 로드", roles("CONCEPT"), 1, "원문-붓꽃");
        f.scopeAnswer = prompt -> """
                {"scopes":[{"course":"c1","role":"REQUIRED","goal":"시험 범위 학습","boundary":"붓꽃·타이타닉까지",
                  "quote":"빅데이터분석은 붓꽃타이타닉까지야",
                  "parts":[{"label":"판다스 실습","materials":["d1"]},{"label":"붓꽃·타이타닉","materials":["d2"]}]}]}
                """;
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "붓꽃 데이터 로드")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[{\"title\":\"붓꽃 로드\",\"description\":\"한다 · 완료: 끝\","
                + "\"expectedMinutes\":30,\"priority\":\"MUST\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\""
                + refOf(prompt, "붓꽃 데이터 로드") + "\"]}]}";

        Generated generated = f.generate(f.spec("오늘 공부하자\n빅데이터분석은 붓꽃타이타닉까지야", null),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        assertThat(f.scopePrompts()).singleElement().satisfies(p -> assertThat(p).contains("붓꽃타이타닉까지야"));
        // 선택 입력과 계획 입력에 서버가 확정한 범위가 실린다.
        assertThat(f.selectionPrompts().get(0)).contains("[이번 요청의 범위").contains("S1.P1 판다스 실습");
        assertThat(f.planPrompts().get(0)).contains("[요청 범위]").contains("S1.P2 붓꽃·타이타닉");

        ScopeReport report = generated.extras().scopeReport();
        assertThat(report.status()).isEqualTo("EXTRACTED");
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("UNHANDLED", "ITEM");
        assertThat(report.parts().get(0).acquisition()).isEqualTo("SHOWN_NOT_SELECTED");
        assertThat(report.requiredUnmet()).isTrue();
        assertThat(report.completion()).isEqualTo("NOT_COMPLETE");
    }

    @Test
    void 범위_추출이_실패해도_계획은_예전처럼_만들어지고_판정은_실패로_남는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> "범위를 못 읽겠어요";
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[{\"title\":\"스택\",\"description\":\"한다 · 완료: 끝\","
                + "\"expectedMinutes\":30,\"priority\":\"MUST\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\""
                + refOf(prompt, "스택 개념") + "\"]}]}";

        Generated generated = f.generate(f.spec("스택까지 공부", null),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        assertThat(generated.items()).hasSize(1);
        assertThat(generated.extras().scopeReport().status()).isEqualTo("FAILED");
        assertThat(generated.extras().scopeReport().completion()).isEqualTo("UNKNOWN");
        assertThat(f.planPrompts().get(0)).doesNotContain("[요청 범위]");
    }
}
