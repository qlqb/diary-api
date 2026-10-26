package com.jungwoo.project.memo.ai.turn;

import com.jungwoo.project.memo.ai.change.ChangeOp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Codex 코드 리뷰 2회차(2026-10-26): 철회 뒤 번호 재사용 금지, 도구 결과 길이 상한. */
class TurnWorkspaceTest {

    private static TurnWorkspace ws() {
        return new TurnWorkspace(1L, 2L, 3L, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26).atTime(10, 0), List.of());
    }

    private static ChangeOp op(String key) {
        ChangeOp op = mock(ChangeOp.class);
        when(op.targetKey()).thenReturn(key);
        return op;
    }

    @Test
    void 철회한_번호는_다시_쓰지_않는다() {
        TurnWorkspace ws = ws();
        ws.stage(op("a"), true, "PROPOSED", null, null);
        TurnWorkspace.Staged second = ws.stage(op("b"), true, "PROPOSED", null, null);
        ws.withdraw(1);
        TurnWorkspace.Staged third = ws.stage(op("c"), true, "PROPOSED", null, null);
        assertThat(third.no()).isEqualTo(3);
        ws.withdraw(3);
        assertThat(ws.staged()).extracting(TurnWorkspace.Staged::no).containsExactly(second.no());
    }

    @Test
    void 도구_결과는_상한까지만_남기고_잘렸다고_표시한다() {
        TurnWorkspace ws = ws();
        String big = "x".repeat(1400);
        for (int i = 0; i < 10; i++) {
            ws.detail("find_schedule", big);
        }
        List<String> d = ws.details();
        assertThat(d.get(d.size() - 1)).startsWith("(이후 도구 결과");
        assertThat(d.stream().filter(s -> s.startsWith("(이후")).count()).isEqualTo(1);
        assertThat(d.stream().mapToInt(String::length).sum()).isLessThan(TurnWorkspace.MAX_DETAIL_CHARS + 100);
    }
}
