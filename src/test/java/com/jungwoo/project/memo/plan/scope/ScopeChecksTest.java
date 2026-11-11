package com.jungwoo.project.memo.plan.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 3단계: 필수 범위가 남았는데 추가 과목을 넣은 판단, 완료 표시. */
class ScopeChecksTest {

    private static final long BIG = 936L;
    private static final long APP = 939L;

    private static RequestScope scope(String id, long course, String title, String role, RequestScope.Part... parts) {
        return new RequestScope(id, course, title, role, "목표", "범위", "q", "q", true, List.of(parts));
    }

    private static RequestScope.Part part(String id, String label, long material) {
        return new RequestScope.Part(id, label, List.of(material), false, false);
    }

    /** 빅데이터(필수) 앞부분이 남고, 스마트앱(가능하면) 항목만 들어간 초안. */
    private static ScopeReport optionalOverRequired() {
        RequestScope big = scope("S1", BIG, "빅데이터분석", RequestScope.REQUIRED,
                part("S1.P1", "판다스 실습", 10L), part("S1.P2", "붓꽃·타이타닉", 11L));
        RequestScope app = scope("S2", APP, "스마트앱프로젝트", RequestScope.OPTIONAL, part("S2.P1", "Navigation", 20L));
        return ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(big, app), Map.of(101L, 10L, 111L, 11L, 201L, 20L),
                Set.of(101L), Map.of(111L, true, 201L, true), Map.of("s11", 111L, "s20", 201L), Map.of(),
                List.of(new ScopeVerdict.ItemRef(BIG, List.of("s11")), new ScopeVerdict.ItemRef(APP, List.of("s20"))),
                List.of());
    }

    @Test
    void 필수_범위가_남았는데_이유_없이_추가_과목을_넣으면_검증_미통과이고_서버가_이유를_적는다() {
        ScopeChecks.Result result = ScopeChecks.check(optionalOverRequired(), List.of(BIG, APP),
                List.of("스택 개념을 먼저 둔다"));

        assertThat(result.report().issues()).containsExactly(ScopeChecks.OPTIONAL_WITHOUT_REASON);
        assertThat(result.serverReason()).contains("빅데이터분석 판다스 실습").contains("항목 없음").contains("자료를 고르지 않음");
        assertThat(result.report().notice()).startsWith("꼭 하기로 한 범위 중 1곳이 아직 계획에 없어요");
    }

    @Test
    void 계획이_그_판단을_설명했으면_통과한다() {
        ScopeChecks.Result result = ScopeChecks.check(optionalOverRequired(), List.of(BIG, APP),
                List.of("빅데이터분석 판다스 실습은 내일로 미루고 시험이 가까운 스마트앱프로젝트를 먼저 넣었다"));

        assertThat(result.report().issues()).isEmpty();
        assertThat(result.serverReason()).isNull();
    }

    @Test
    void 완료_문장은_판정으로_정한다() {
        RequestScope big = scope("S1", BIG, "빅데이터분석", RequestScope.REQUIRED, part("S1.P1", "붓꽃", 11L));
        RequestScope app = scope("S2", APP, "스마트앱프로젝트", RequestScope.OPTIONAL, part("S2.P1", "Navigation", 20L));
        ScopeReport requiredOnly = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(big, app), Map.of(111L, 11L),
                Set.of(), Map.of(111L, true), Map.of("s11", 111L), Map.of(),
                List.of(new ScopeVerdict.ItemRef(BIG, List.of("s11"))), List.of());
        assertThat(ScopeChecks.check(requiredOnly, List.of(BIG), List.of()).report().notice())
                .isEqualTo("꼭 하기로 한 범위는 담았어요. '가능하면' 범위 중 1곳은 이번 계획에 없어요.");

        ScopeReport all = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(big), Map.of(111L, 11L), Set.of(),
                Map.of(111L, true), Map.of("s11", 111L), Map.of(), List.of(new ScopeVerdict.ItemRef(BIG, List.of("s11"))),
                List.of());
        assertThat(ScopeChecks.check(all, List.of(BIG), List.of()).report().notice()).isEqualTo("말한 범위를 모두 계획에 담았어요.");

        assertThat(ScopeChecks.check(ScopeReport.empty(ScopeReport.NONE), List.of(), List.of()).report().notice()).isNull();
    }
    @Test
    void 관련_없는_과목_얘기만_있으면_판단_설명이_아니고_판정하지_못한_경우는_그렇게_말한다() {
        ScopeChecks.Result unrelated = ScopeChecks.check(optionalOverRequired(), List.of(BIG, APP),
                List.of("한국사는 이미 끝냈다"));
        assertThat(unrelated.report().issues()).containsExactly(ScopeChecks.OPTIONAL_WITHOUT_REASON);
        assertThat(unrelated.serverReason()).isNotNull();

        ScopeReport dropped = new ScopeReport(ScopeReport.EXTRACTED, List.of(), List.of(), false, List.of(), List.of(),
                ScopeReport.Completion.UNKNOWN.name(), true);
        assertThat(ScopeChecks.check(dropped, List.of(), List.of()).report().notice()).contains("판단하지 못했어요");
        assertThat(ScopeChecks.check(ScopeReport.empty(ScopeReport.FAILED), List.of(), List.of()).report().notice())
                .contains("판단하지 못했어요");
    }
}
