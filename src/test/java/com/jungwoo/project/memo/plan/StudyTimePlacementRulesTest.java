package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 배치·확정이 말한 시간 조건을 지키는 규칙: 자정에서 나누기, 범위 밖·제외 구간·항목끼리 겹침 거절. */
class StudyTimePlacementRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    @Test
    void 자정을_넘는_남는_시간은_자정에서_나뉘어_다음_날짜_칸이_된다() {
        List<AvailabilityWindow> split = PlanPlacementService.splitAtMidnight(List.of(new AvailabilityWindow(
                DAY.atTime(22, 0), DAY.plusDays(1).atTime(1, 0), AvailabilitySource.CURRENT_CONVERSATION,
                AvailabilityConfidence.HIGH, "말한 시간")));
        assertThat(split).hasSize(2);
        assertThat(split.get(0).endAt()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(split.get(1).startAt()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(split.get(1).endAt()).isEqualTo(DAY.plusDays(1).atTime(1, 0));
    }

    private static AiProposalApplyRequest.EditedProposalItem item(long id, LocalDateTime start, LocalDateTime end) {
        AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(id);
        edit.setPlacementType(PlacementType.TIME_FIXED);
        edit.setScheduledStartAt(start);
        edit.setScheduledEndAt(end);
        return edit;
    }

    @Test
    void 확정_항목이_말한_범위_밖이거나_식사_시간이거나_서로_겹치면_거절_사유가_남는다() {
        StudyTimeCondition c = new StudyTimeCondition(StudyTimeCondition.ADOPTED,
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(14, 0), LocalTime.of(20, 0), false, null)),
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(18, 0), LocalTime.of(19, 0), false, "저녁")),
                null, null, "FILL", List.of(), List.of(), List.of());

        List<Map<String, Object>> v = PlanConfirmScheduleGuard.studyTimeViolations(List.of(
                item(1, DAY.atTime(14, 0), DAY.atTime(15, 0)),   // 정상
                item(2, DAY.atTime(20, 0), DAY.atTime(21, 0)),   // 범위 밖
                item(3, DAY.atTime(18, 30), DAY.atTime(19, 0)),  // 식사 시간
                item(4, DAY.atTime(14, 30), DAY.atTime(15, 30))  // 1번과 겹침
        ), c, DAY, DAY);

        assertThat(v).extracting(m -> m.get("proposalItemId") + ":" + m.get("reason"))
                .contains("2:OUTSIDE_STUDY_TIME", "3:IN_EXCLUSION", "1:ITEM_OVERLAP")
                .doesNotContain("1:OUTSIDE_STUDY_TIME");
    }

    @Test
    void 자정에_끝나는_범위의_23시_칸과_다음_날_새벽_칸은_범위_안이다() {
        StudyTimeCondition c = new StudyTimeCondition(StudyTimeCondition.ADOPTED,
                List.of(new StudyTimeCondition.Range(DAY, LocalTime.of(22, 0), LocalTime.of(1, 0), true, null)),
                List.of(), null, null, "FILL", List.of(), List.of(), List.of());
        List<Map<String, Object>> v = PlanConfirmScheduleGuard.studyTimeViolations(List.of(
                item(1, DAY.atTime(23, 0), DAY.plusDays(1).atStartOfDay()),
                item(2, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(1).atTime(1, 0))), c, DAY, DAY.plusDays(1));
        assertThat(v).isEmpty();
    }
}
