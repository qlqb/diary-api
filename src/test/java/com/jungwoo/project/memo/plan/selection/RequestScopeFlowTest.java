package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
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

        // 2단계: 선택이 앞부분(판다스 실습)을 하나도 고르지 않아 서버가 그 자료의 구간을 보충해 원문을 실었다.
        assertThat(f.planPrompts().get(0)).contains("원문-판다스");

        ScopeReport report = generated.extras().scopeReport();
        assertThat(report.status()).isEqualTo("EXTRACTED");
        // 원문은 실렸지만 계획 항목이 그 부분을 다루지 않았다 — 확보와 반영은 따로 판정된다.
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("UNHANDLED", "ITEM");
        assertThat(report.parts().get(0).acquisition()).isIn("DELIVERED_UNIT", "EXCERPT_ONLY");
        assertThat(report.requiredUnmet()).isTrue();
        assertThat(report.completion()).isEqualTo("NOT_COMPLETE");
        // 3단계: 완료 표시는 판정으로 — 남은 필수 범위를 맨 위 문장이 말한다. 계획 입력에는 범위 규칙이 실린다.
        assertThat(report.notice()).contains("판다스 실습");
        assertThat(f.planPrompts().get(0)).contains("첫 미학습\"은 범위 안의 순서 참고일 뿐").contains("지어내지 않고");
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
    @Test
    void 범위_추출_응답이_구분자를_빼먹어도_본문에서_범위를_읽는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        f.scopeWithoutDelimiter = true;
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> "범위를 뽑았어요.\n{\"scopes\":[{\"course\":\"c1\",\"role\":\"REQUIRED\",\"quote\":\"스택까지\","
                + "\"parts\":[{\"label\":\"스택\",\"materials\":[\"d1\"]}]}]}";
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[{\"title\":\"스택\",\"description\":\"한다 · 완료: 끝\","
                + "\"expectedMinutes\":30,\"priority\":\"MUST\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\""
                + refOf(prompt, "스택 개념") + "\"]}]}";

        Generated generated = f.generate(f.spec("자료구조는 스택까지 공부", null),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        ScopeReport report = generated.extras().scopeReport();
        assertThat(report.status()).isEqualTo("EXTRACTED");
        assertThat(report.scopes()).singleElement().satisfies(s -> {
            assertThat(s.verified()).isTrue();
            assertThat(s.parts()).extracting(com.jungwoo.project.memo.plan.scope.RequestScope.Part::label).containsExactly("스택");
        });
        assertThat(report.parts().get(0).reflection()).isEqualTo("ITEM");
    }

    private static com.jungwoo.project.memo.ai.brief.PlanBriefItem timeItem(String text, String speaker, boolean accepted) {
        return new com.jungwoo.project.memo.ai.brief.PlanBriefItem(1, "TIME_BUDGET", text, speaker, accepted, false, false,
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SCOPE_THIS_DRAFT, 10L,
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_USER.equals(speaker) ? 10L : null, null, null, null,
                null, 1, java.util.List.of(), java.time.LocalDateTime.of(2026, 9, 13, 9, 0));
    }

    private static void brief(PlanSelectionFixture f, com.jungwoo.project.memo.ai.brief.PlanBriefItem item) {
        org.mockito.Mockito.when(f.briefService.load(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.jungwoo.project.memo.ai.brief.PlanBriefService.View(5L, 77L, 3, java.util.List.of(item), null));
    }

    private static com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Origin origin() {
        return new com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Origin(77L, null, "req-1", null, null);
    }

    @Test
    void 범위_추출이_원문에서_확인한_시간은_AI_후보로_떨어진_합의_대신_예산을_정한다() {
        // 대화 2164 재현: "4시까지"는 상담 합의에서 근거 없는 AI 후보였다 — 예산은 하루 기본 창 그대로였다.
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        brief(f, timeItem("오늘 4시까지 공부한다. (쓸 수 있는 시간 하루 · ~16:00)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_ASSISTANT, false));
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> """
                {"scopes":[],"studyTime":{"minutes":null,"per":"DAY","from":null,"until":"16:00","quote":"오늘 오후4시까지 가능해"}}
                """;
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> {
            String ref = refOf(prompt, "스택 개념");
            return "{\"title\":\"t\",\"items\":[" + item("스택 과제", ref, 300, "MUST") + ","
                    + item("스택 응용", ref, 200, "SHOULD") + "]}";
        };

        Generated generated = f.generate(f.spec(com.jungwoo.project.memo.plan.scope.PlanScopeExtractor.CONVERSATION_HEADER + "\n- 스택 과제 끝내고 싶어\n- 오늘 오후4시까지 가능해\n", origin()),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        // 픽스처 가용 창은 첫날 09:00부터 하나 — 09:00~16:00의 남는 시간(420분)이 예산이다(강도 비율 600분이 아니다).
        assertThat(f.scopePrompts().get(0)).contains("[시각 기준] 09:00");
        assertThat(generated.targetMinutes()).isEqualTo(420);
        // 생성 뒤에도 그 시간을 넘는 항목은 미룬다
        assertThat(generated.items()).extracting(i -> i.title()).containsExactly("스택 과제");
        assertThat(generated.strategy().deferred()).extracting(d -> d.title()).contains("스택 응용");
        assertThat(f.planPrompt()).contains("[사용자가 말한 시간]").contains("~16:00");
        ScopeReport report = generated.extras().scopeReport();
        assertThat(report.status()).isEqualTo("EXTRACTED");
        assertThat(report.studyTime().until()).isEqualTo("16:00");
    }

    @Test
    void 원문과_값이_다른_시간은_예산에_쓰지_않는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> """
                {"scopes":[],"studyTime":{"until":"12:00","quote":"오늘 오후4시까지 가능해"}}
                """;
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[{\"title\":\"스택\",\"description\":\"한다 · 완료: 끝\","
                + "\"expectedMinutes\":30,\"priority\":\"MUST\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\""
                + refOf(prompt, "스택 개념") + "\"]}]}";

        Generated generated = f.generate(f.spec(com.jungwoo.project.memo.plan.scope.PlanScopeExtractor.CONVERSATION_HEADER + "\n- 오늘 오후4시까지 가능해\n", null),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        assertThat(f.planPrompt()).doesNotContain("[사용자가 말한 시간]");
        assertThat(generated.extras().scopeReport().studyTime()).isNull();
    }

    @Test
    void 승인되지_않은_AI_정리_시간은_범위_추출의_시간을_막지_못한다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        // 근거만 붙은 AI 정리(원문 대조 통과, 미승인) — 분량 "30분"을 빠뜨렸다
        com.jungwoo.project.memo.ai.brief.PlanBriefItem grounded = new com.jungwoo.project.memo.ai.brief.PlanBriefItem(1,
                "TIME_BUDGET", "오후 4시까지 (쓸 수 있는 시간 하루 · ~16:00)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_ASSISTANT, false, false, false,
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SCOPE_THIS_DRAFT, 10L, null, null, null, null, null, 1, java.util.List.of(),
                java.time.LocalDateTime.of(2026, 9, 13, 9, 0), null, null, null, null, "오늘 오후4시까지 30분만", 10L,
                "오늘 오후4시까지 30분만 공부할 수 있어");
        brief(f, grounded);
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> """
                {"scopes":[],"studyTime":{"minutes":30,"per":"DAY","until":"16:00","quote":"오늘 오후4시까지 30분만 공부할 수 있어"}}
                """;
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[" + item("스택", refOf(prompt, "스택 개념"), 30, "MUST") + "]}";

        Generated generated = f.generate(f.spec(com.jungwoo.project.memo.plan.scope.PlanScopeExtractor.CONVERSATION_HEADER + "\n- 오늘 오후4시까지 30분만 공부할 수 있어\n", origin()),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        // 7일 계획 · 하루 30분 = 210분. AI 정리(~16:00만)를 썼다면 420분이었다.
        assertThat(generated.targetMinutes()).isEqualTo(210);
        assertThat(generated.extras().scopeReport().studyTime().minutes()).isEqualTo(30);
    }

    @Test
    void 사용자가_확인한_시간_합의가_있으면_합의가_먼저고_추출한_시간은_보고에_남기지_않는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.enableScopes();
        brief(f, timeItem("오늘 한 시간만 (쓸 수 있는 시간 60분 · 계획 전체)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_USER, true));
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        f.scopeAnswer = prompt -> """
                {"scopes":[],"studyTime":{"until":"16:00","quote":"오늘 오후4시까지 가능해"}}
                """;
        f.selectionAnswer = prompt -> selection(java.util.List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")),
                java.util.List.of(), java.util.List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":[" + item("스택", refOf(prompt, "스택 개념"), 30, "MUST") + "]}";

        Generated generated = f.generate(f.spec(com.jungwoo.project.memo.plan.scope.PlanScopeExtractor.CONVERSATION_HEADER + "\n- 오늘 오후4시까지 가능해\n", origin()),
                com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        assertThat(generated.targetMinutes()).isEqualTo(60);
        assertThat(generated.extras().scopeReport().studyTime()).isNull();
    }

    @Test
    void 사용자_합의가_있으면_뒤에_붙은_AI_정리보다_먼저고_승인한_AI_제안도_사용자_합의다() {
        com.jungwoo.project.memo.ai.brief.PlanBriefItem user = timeItem("오늘 한 시간만 (쓸 수 있는 시간 60분 · 계획 전체)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_USER, true);
        com.jungwoo.project.memo.ai.brief.PlanBriefItem groundedAi = new com.jungwoo.project.memo.ai.brief.PlanBriefItem(2,
                "TIME_BUDGET", "오후 4시까지 (쓸 수 있는 시간 하루 · ~16:00)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_ASSISTANT, false, false, false,
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SCOPE_THIS_DRAFT, 11L, null, null, null, null, null, 1,
                java.util.List.of(), java.time.LocalDateTime.of(2026, 9, 13, 9, 5), null, null, null, null, "오후4시까지 가능해",
                11L, "오후4시까지 가능해");
        PlanBriefService.TimeBudget chosen = PlanBriefService.chooseTimeBudget(java.util.List.of(user, groundedAi),
                new PlanBriefService.TimeBudget(null, true, null, "18:00"));
        assertThat(chosen.minutes()).isEqualTo(60);
        assertThat(chosen.hasWindow()).isFalse();
        // 확인된 합의가 없으면 범위 추출의 시간, 그것도 없으면 근거만 붙은 AI 정리
        assertThat(PlanBriefService.chooseTimeBudget(java.util.List.of(groundedAi),
                new PlanBriefService.TimeBudget(null, true, null, "18:00")).until()).isEqualTo("18:00");
        assertThat(PlanBriefService.chooseTimeBudget(java.util.List.of(groundedAi), null).until()).isEqualTo("16:00");

        // AI가 제안한 6시를 사용자가 승인했다(원문 근거 없음) — 사용자의 합의이고 범위 추출보다 먼저다
        com.jungwoo.project.memo.ai.brief.PlanBriefItem approved = timeItem("6시까지 어때요 (쓸 수 있는 시간 하루 · ~18:00)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_ASSISTANT, true);
        assertThat(PlanBriefService.confirmedTimeBudgetOf(java.util.List.of(approved)).until()).isEqualTo("18:00");
        assertThat(PlanBriefService.chooseTimeBudget(java.util.List.of(approved),
                new PlanBriefService.TimeBudget(null, true, null, "16:00")).until()).isEqualTo("18:00");
    }

    private static String item(String title, String ref, int minutes, String priority) {
        return "{\"title\":\"" + title + "\",\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":" + minutes
                + ",\"priority\":\"" + priority + "\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\"" + ref + "\"]}";
    }
}
