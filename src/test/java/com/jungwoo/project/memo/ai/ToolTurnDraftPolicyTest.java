package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.draft.DraftState;
import com.jungwoo.project.memo.ai.draft.DraftStatus;
import com.jungwoo.project.memo.ai.draft.DraftTurnResolver;
import com.jungwoo.project.memo.ai.draft.DraftType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Codex 코드 리뷰 S5(2026-10-26): 도구 단계가 변경을 올린 턴은 일정 후보 승격만 빼고, draft 저장·계획 OFFER는 그대로 둔다. */
class ToolTurnDraftPolicyTest {

    private static DraftTurnResolver.Outcome propose(List<DraftState> drafts) {
        return new DraftTurnResolver.Outcome(drafts, Set.of(), DraftTurnResolver.Action.PROPOSE, true, drafts, null,
                "일정 후보를 만들었어요", List.of(), Map.of(), List.of());
    }

    @Test
    void 일정_draft만_있으면_승격하지_않고_닫으며_모델_답을_쓴다() {
        DraftState schedule = DraftState.newDraft("d1", 1L, 2L, DraftType.CREATE_SCHEDULE, "알바");
        DraftTurnResolver.Outcome out = AiConversationService.withoutSchedulePromotion(propose(List.of(schedule)));
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.CHAT);
        assertThat(out.serverReply()).isNull();
        assertThat(out.touchesDrafts()).isTrue(); // draft 상태는 저장된다
        assertThat(schedule.getStatus()).isEqualTo(DraftStatus.CANCELLED);
    }

    @Test
    void 기간_계획_OFFER는_그대로_둔다() {
        DraftState plan = DraftState.newDraft("d2", 1L, 2L, DraftType.CREATE_PERIOD_PLAN, "계획");
        DraftTurnResolver.Outcome in = propose(List.of(plan));
        assertThat(AiConversationService.withoutSchedulePromotion(in)).isSameAs(in);
        assertThat(plan.getStatus()).isNotEqualTo(DraftStatus.CANCELLED);
    }
}
