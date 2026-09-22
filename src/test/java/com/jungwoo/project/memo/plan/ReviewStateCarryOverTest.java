package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.dto.PlanReviewState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** handoff T10 — 다시 만들기가 사용자의 직접 편집을 날리지 않는다. */
class ReviewStateCarryOverTest {

    private static AiProposalItemResponse item(long id, String title, Long courseId, int minutes, String priority) {
        return AiProposalItemResponse.builder().proposalItemId(id).title(title).courseId(courseId).expectedMinutes(minutes)
                .priority(priority).placementType(PlacementType.UNSCHEDULED).build();
    }

    private static EditedProposalItem edit(long id, Integer minutes, String priority) {
        EditedProposalItem e = new EditedProposalItem();
        e.setProposalItemId(id);
        e.setExpectedMinutes(minutes);
        e.setPriority(priority);
        return e;
    }

    @Test
    void 고친_분량과_뺀_항목은_새_항목_id로_옮겨지고_AI가_값을_바꿨으면_충돌로_알리되_사용자_값을_유지한다() {
        List<AiProposalItemResponse> old = List.of(item(1, "자료구조 · 스택 구현", 1L, 60, "MUST"),
                item(2, "영어 · 단어 30개", 2L, 30, "SHOULD"), item(3, "네트워크 · OSI 정리", 3L, 45, "SHOULD"));
        PlanReviewState state = new PlanReviewState(4, "토요일 계획", List.of(2L),
                List.of(edit(1, 20, null), edit(3, 30, "OPTIONAL")), Map.of("familiarity", "PARTIAL"), null);
        // 새 초안: 스택은 40분으로 바뀌었고(충돌), OSI는 그대로, 영어는 또 나왔다.
        List<AiProposalItemResponse> next = List.of(item(11, "자료구조 · 스택  구현", 1L, 40, "MUST"),
                item(12, "영어 · 단어 30개", 2L, 30, "SHOULD"), item(13, "네트워크 · OSI 정리", 3L, 45, "SHOULD"));

        ReviewStateCarryOver.Result result = ReviewStateCarryOver.carry(state, old, next);

        assertThat(result.state().title()).isEqualTo("토요일 계획");
        assertThat(result.state().version()).isEqualTo(1);
        assertThat(result.state().excludedProposalItemIds()).containsExactly(12L);
        assertThat(result.state().answers()).containsEntry("familiarity", "PARTIAL");
        assertThat(result.state().editedItems()).extracting(EditedProposalItem::getProposalItemId, EditedProposalItem::getExpectedMinutes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11L, 20), org.assertj.core.groups.Tuple.tuple(13L, 30));
        assertThat(result.state().editedItems().get(1).getPriority()).isEqualTo("OPTIONAL");
        assertThat(result.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.field()).isEqualTo("expectedMinutes");
            assertThat(c.yours()).isEqualTo("20분");
            assertThat(c.suggested()).isEqualTo("40분");
        });
    }

    @Test
    void 고친_항목이_새_초안에_없으면_만들어_넣지_않고_그_사실을_알린다() {
        ReviewStateCarryOver.Result result = ReviewStateCarryOver.carry(
                new PlanReviewState(1, null, List.of(), List.of(edit(1, 20, null)), Map.of(), null),
                List.of(item(1, "자료구조 · 스택 구현", 1L, 60, "MUST")), List.of(item(11, "자료구조 · 큐 구현", 1L, 60, "MUST")));

        assertThat(result.state()).isNull();
        assertThat(result.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.field()).isEqualTo("item");
            assertThat(c.title()).isEqualTo("자료구조 · 스택 구현");
            assertThat(c.suggested()).isNull();
        });
    }

    @Test
    void 제목이_같은_일일_반복_항목은_날짜로_구분해_짝짓고_다른_프로젝트의_같은_제목과는_짝짓지_않는다() {
        AiProposalItemResponse mon = item(1, "영어 · 15분 듣기", 2L, 15, "SHOULD");
        mon.setPlacementType(PlacementType.DATE_ONLY);
        mon.setTargetDate(LocalDate.of(2026, 9, 21));
        AiProposalItemResponse tue = item(2, "영어 · 15분 듣기", 2L, 15, "SHOULD");
        tue.setPlacementType(PlacementType.DATE_ONLY);
        tue.setTargetDate(LocalDate.of(2026, 9, 22));
        AiProposalItemResponse newMon = item(11, "영어 · 15분 듣기", 2L, 15, "SHOULD");
        newMon.setPlacementType(PlacementType.DATE_ONLY);
        newMon.setTargetDate(LocalDate.of(2026, 9, 21));
        AiProposalItemResponse newTue = item(12, "영어 · 15분 듣기", 2L, 15, "SHOULD");
        newTue.setPlacementType(PlacementType.DATE_ONLY);
        newTue.setTargetDate(LocalDate.of(2026, 9, 22));
        AiProposalItemResponse otherProject = item(13, "영어 · 15분 듣기", 9L, 15, "SHOULD");

        ReviewStateCarryOver.Result result = ReviewStateCarryOver.carry(
                new PlanReviewState(1, null, List.of(2L), List.of(), Map.of(), null), List.of(mon, tue),
                List.of(otherProject, newMon, newTue));

        assertThat(result.state().excludedProposalItemIds()).containsExactly(12L);
    }

    @Test
    void 검토_상태가_없으면_아무것도_하지_않는다() {
        assertThat(ReviewStateCarryOver.carry(null, List.of(), List.of()).state()).isNull();
    }
}
