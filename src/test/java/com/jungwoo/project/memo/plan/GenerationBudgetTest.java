package com.jungwoo.project.memo.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 상한은 로그가 아니라 실행 제한이다. 정상 3 · 복구 1 · 전체 4 · 조회 2라운드가 기본값이고, 넘는 호출은 허락되지 않는다. */
class GenerationBudgetTest {

    @Test
    void 정상_호출_셋_뒤에는_정상_호출을_더_허락하지_않고_복구는_한_번만() {
        GenerationBudget b = GenerationBudget.defaults();
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
}
