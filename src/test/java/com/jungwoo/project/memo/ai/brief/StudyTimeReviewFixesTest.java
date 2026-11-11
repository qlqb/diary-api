package com.jungwoo.project.memo.ai.brief;

import com.jungwoo.project.memo.plan.StudyTimeBudget;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Codex 코드 리뷰 1회차(2026-10-26) 지적의 회귀 테스트: 새벽 "1시까지", 단위가 모호한 분량. */
class StudyTimeReviewFixesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    @Test
    void 새벽에_말한_1시까지는_지금부터_1시까지로_남는다() {
        StudyTimeCondition c = StudyTimeInterpreter.interpret(
                List.of(new StudyTimeInterpreter.Part("WINDOW", "새벽 1시까지", null, "01:00", null, null, null, null)), null,
                List.of(new StudyTimeInterpreter.Utterance(1L, "새벽 1시까지 할게", DAY.atTime(0, 30))), DAY, 1);
        assertThat(c.adopted()).isTrue();
        LocalDateTime[] on = c.windows().get(0).on(DAY, StudyTimeInterpreter.DEFAULT_START);
        assertThat(on).isNotNull();
        assertThat(on[0]).isEqualTo(DAY.atTime(0, 30));
        assertThat(on[1]).isEqualTo(DAY.atTime(1, 0));
    }

    @Test
    void 여러_날_계획에서_단위가_모호한_분량은_예산에_쓰지_않는다() {
        StudyTimeCondition c = StudyTimeInterpreter.interpret(
                List.of(new StudyTimeInterpreter.Part("AMOUNT", "3시간만", null, null, null, 180, "DAY", null)), null,
                List.of(new StudyTimeInterpreter.Utterance(1L, "3시간만 공부할래", DAY.atTime(13, 0))), DAY, 3);
        assertThat(c.status()).isEqualTo(StudyTimeCondition.AMBIGUOUS);
        assertThat(c.totalFor(3)).isNull();
        StudyTimeBudget b = StudyTimeBudget.compute(c, null, List.of(new AvailabilityWindow(DAY.atTime(9, 0),
                DAY.atTime(19, 0), AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본")), List.of(), 3,
                PlanIntensity.NORMAL);
        assertThat(b.requestedMinutes()).isNull(); // 하루 3시간으로 늘려 잡지 않는다 — 물어본 뒤에 쓴다
    }

    @Test
    void 이틀_계획의_첫날만_말한_범위는_다음_날_항목을_막지_않는다() {
        StudyTimeCondition c = new StudyTimeCondition(StudyTimeCondition.ADOPTED,
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(14, 0), LocalTime.of(20, 0), false, null)),
                List.of(), null, null, "FILL", List.of(), List.of(), List.of());
        var edit = new com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(1L);
        edit.setPlacementType(com.jungwoo.project.memo.execution.domain.PlacementType.TIME_FIXED);
        edit.setScheduledStartAt(DAY.plusDays(1).atTime(10, 0));
        edit.setScheduledEndAt(DAY.plusDays(1).atTime(11, 0));
        assertThat(com.jungwoo.project.memo.plan.PlanConfirmScheduleGuardAccess.violations(List.of(edit), c, DAY, DAY.plusDays(1)))
                .isEmpty();
    }
}
