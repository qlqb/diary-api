package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 목적은 화면에서 고른 값이 먼저이고, 없으면 상담 합의(PURPOSE) 중 마지막으로 고치거나 받아들인 것이다(배열 순서가 아니다).
 */
class PlanPurposeResolutionTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 5);

    private static PlanBriefItem purpose(int id, String text, LocalDateTime updatedAt) {
        return new PlanBriefItem(id, "PURPOSE", text, PlanBriefItem.SPEAKER_USER, true, false, false,
                PlanBriefItem.SCOPE_THIS_DRAFT, null, null, null, null, null, null, 1, List.of(), updatedAt);
    }

    private static PeriodPlanDraftGenerator.Spec spec(String purpose) {
        return new PeriodPlanDraftGenerator.Spec(1L, START, START.plusDays(6), null, null, null, List.of(), List.of(),
                List.of(), List.of(), null, purpose);
    }

    @Test
    void 마지막으로_고친_목적이_앞에_있어도_그것이_지금_목적이다() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 4, 9, 0);
        PlanBriefService.View brief = new PlanBriefService.View(1L, 2L, 3, List.of(
                purpose(1, "EXAM: 중간고사", t.plusHours(2)),
                purpose(2, "PREVIEW: 다음 수업 예습", t)), null);

        assertThat(PeriodPlanDraftGenerator.purposeOf(spec(null), brief)).isEqualTo(PlanPurpose.EXAM);
    }

    @Test
    void 화면에서_고른_목적이_합의보다_먼저다() {
        PlanBriefService.View brief = new PlanBriefService.View(1L, 2L, 3, List.of(
                purpose(1, "EXAM: 중간고사", LocalDateTime.now())), null);

        assertThat(PeriodPlanDraftGenerator.purposeOf(spec("SELF_STUDY"), brief)).isEqualTo(PlanPurpose.SELF_STUDY);
        assertThat(PeriodPlanDraftGenerator.purposeOf(spec(null), null)).isEqualTo(PlanPurpose.UNSPECIFIED);
    }

    @Test
    void 초안을_저장하며_범위를_묶어도_마지막으로_고친_목적이_그대로다() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 4, 9, 0);
        PlanBriefItem exam = purpose(1, "EXAM: 중간고사", t.plusHours(2));
        PlanBriefItem preview = purpose(2, "PREVIEW: 다음 수업 예습", t);
        LocalDateTime saved = t.plusHours(5);
        // markProposal이 초안 흐름을 묶는다 — 사용자가 합의를 고친 것이 아니다.
        PlanBriefService.View afterSave = new PlanBriefService.View(1L, 2L, 4, List.of(
                exam.bound(77L, START, START.plusDays(6), saved), preview.bound(77L, START, START.plusDays(6), saved)), 77L);

        assertThat(afterSave.items().get(0).updatedAt()).isEqualTo(t.plusHours(2));
        PeriodPlanDraftGenerator.Spec regenerate = new PeriodPlanDraftGenerator.Spec(1L, START, START.plusDays(6), null,
                null, null, List.of(), List.of(), List.of(), List.of(),
                new PeriodPlanDraftGenerator.Origin(2L, null, "k", 77L, 77L), null);
        assertThat(PeriodPlanDraftGenerator.purposeOf(regenerate, afterSave)).isEqualTo(PlanPurpose.EXAM);
    }
}
