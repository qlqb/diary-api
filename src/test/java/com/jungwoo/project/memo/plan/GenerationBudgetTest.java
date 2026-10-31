package com.jungwoo.project.memo.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 상한은 로그가 아니라 실행 제한이다. 정상 3 · 복구 1 · 전체 4 · 조회 2라운드가 기본값이고, 넘는 호출은 허락되지 않는다. */
class GenerationBudgetTest {

    @Test
    void 정상_호출_셋_뒤에는_정상_호출을_더_허락하지_않고_복구는_한_번만() {
        GenerationBudget b = new GenerationBudget(3, 1, 4, 2);
        b.record(GenerationBudget.Call.SELECTION, 1, 100, null, 10, true, null);
        b.record(GenerationBudget.Call.SELECTION_EXPAND, 2, 200, null, 10, true, null);
        assertThat(b.canCallNormal()).isTrue();
        b.record(GenerationBudget.Call.PLAN, 1, 300, 50, 10, true, null);
        assertThat(b.canCallNormal()).isFalse();
        assertThat(b.canRecover()).isTrue();
        b.record(GenerationBudget.Call.PLAN_RECOVERY, 1, 300, 50, 10, true, null);
        assertThat(b.canRecover()).isFalse();
        assertThat(b.totalCalls()).isEqualTo(4);
        assertThat(b.inputTokens()).isEqualTo(900);
        assertThat(b.summary().calls()).hasSize(4);
    }

    @Test
    void 전체_상한은_복구_호출까지_합쳐_센다() {
        GenerationBudget b = new GenerationBudget(3, 2, 4, 2);
        b.record(GenerationBudget.Call.PLAN, 1, null, null, 0, false, "실패");
        b.record(GenerationBudget.Call.PLAN_RECOVERY, 1, null, null, 0, false, "실패");
        b.record(GenerationBudget.Call.PLAN_RECOVERY, 1, null, null, 0, true, null);
        // 정상 1 + 복구 2 = 3 < 4 이지만 복구 상한 2에 닿았다.
        assertThat(b.canRecover()).isFalse();
        assertThat(b.canCallNormal()).isTrue();
        b.record(GenerationBudget.Call.PLAN_MORE_EVIDENCE, 2, null, null, 0, true, null);
        assertThat(b.canCallNormal()).isFalse();
    }

    @Test
    void 조회_라운드는_첫_조회를_포함해_상한까지만() {
        GenerationBudget b = GenerationBudget.defaults();
        b.retrievalRound();
        assertThat(b.canRetrieveAgain()).isTrue();
        b.retrievalRound();
        assertThat(b.canRetrieveAgain()).isFalse();
    }

    @Test
    void 선택이_두_번을_써도_추가_읽기_뒤_계획_호출_한_번이_남는다() {
        // 2026-09-19 진단: 선택 2 + 계획 1 = 상한 3이라 추가 읽기 요청 9건이 전부 거절됐다.
        GenerationBudget b = GenerationBudget.defaults();
        b.record(GenerationBudget.Call.SELECTION, 1, 3000, null, 10, true, null);
        b.record(GenerationBudget.Call.SELECTION_EXPAND, 2, 14000, null, 10, true, null);
        b.record(GenerationBudget.Call.PLAN, 1, 17000, 4000, 10, true, null);
        assertThat(b.canCallOptional("추가 읽기 뒤 계획", 0, 20000, 0)).isTrue();
        b.record(GenerationBudget.Call.PLAN_MORE_EVIDENCE, 2, 20000, 4000, 10, true, null);
        assertThat(b.canCallOptional("한 번 더", 0, 20000, 0)).isFalse();
        assertThat(b.refusals()).hasSize(1).first().asString().contains("호출 횟수");
    }

    @Test
    void 선택적_호출은_최종_계획_호출을_남겨_둘_수_있을_때만_허용한다() {
        GenerationBudget b = new GenerationBudget(2, 1, 3, 2);
        b.record(GenerationBudget.Call.SELECTION, 1, 3000, null, 10, true, null);
        // 펼친 선택을 하면 최종 계획을 부를 호출이 남지 않는다.
        assertThat(b.canCallOptional("펼친 자료 선택", 1, 14000, 17000)).isFalse();
        assertThat(b.canCallNormal()).isTrue();
    }

    @Test
    void 선택적_호출은_토큰_합과_시작_기한도_본다() {
        long[] now = {0};
        GenerationBudget b = new GenerationBudget(new GenerationBudget.Limits(4, 1, 5, 2, 50000, 170_000, 75_000),
                () -> now[0]);
        b.record(GenerationBudget.Call.SELECTION, 1, 30000, null, 10, true, null);
        assertThat(b.canCallOptional("펼친 자료 선택", 1, 14000, 17000)).isFalse(); // 30k + 14k + 17k > 50k
        assertThat(b.refusals().get(0)).contains("입력 토큰");
        assertThat(b.canCallOptional("작은 호출", 0, 1000, 0)).isTrue();
        now[0] = 80_000;
        assertThat(b.canCallOptional("작은 호출", 0, 1000, 0)).isFalse();
        assertThat(b.refusals().get(1)).contains("경과 시간");
        assertThat(b.canCallNormal()).isTrue(); // 필수 호출은 전체 기한 안이면 한다.
        now[0] = 171_000;
        assertThat(b.canCallNormal()).isFalse();
        assertThat(b.canRecover()).isFalse();
    }
}
