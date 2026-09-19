package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.learning.TopicChangeProposalMapper;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposalStatus;
import com.jungwoo.project.memo.learning.structure.ProposedTopicIndex;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.END;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.START;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.USER;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.refOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.selection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * handoff T04(승인 전 구조 읽기)·T07/T12(말한 시간이 초안까지 유지)·T13(일정 조회 오류는 "일정 없음"이 되지 않는다).
 */
class ConsultConditionsFlowTest {

    private static String roles(String... roles) {
        return "[" + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList()) + "]";
    }

    private static TopicChangeProposal proposal(long id, long materialId, String hash, String ops) {
        return TopicChangeProposal.builder().proposalId(id).userId(USER).courseId(1L).materialId(materialId)
                .fileHash(hash).status(TopicChangeProposalStatus.PROPOSED).opsJson(ops).build();
    }

    @Test
    void T04_승인_전_구조_제안은_읽기_전용_색인으로만_쓰이고_옛_버전과_삭제된_자료의_제안은_읽지_않는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "ch01.pdf", 1L);
        f.material(11, "ch02.pdf", 1L);
        f.section(101, 10, "스택의 정의", roles("CONCEPT"), 1, "원문-스택");
        f.section(102, 10, "스택 연습", roles("EXERCISE"), 2, "원문-스택연습");
        f.section(111, 11, "큐의 정의", roles("CONCEPT"), 1, "원문-큐");
        TopicChangeProposalMapper proposals = mock(TopicChangeProposalMapper.class);
        when(proposals.findOpenByCourseId(1L, USER)).thenReturn(List.of(
                proposal(51, 10, "h10", "[{\"op\":\"ADD\",\"tempId\":\"n1\",\"title\":\"스택\",\"sectionIds\":[101,102]}]"),
                // 자료가 바뀌기 전(옛 해시)의 제안 — 읽지 않는다.
                proposal(52, 11, "OLD-HASH", "[{\"op\":\"ADD\",\"tempId\":\"n1\",\"title\":\"큐(옛 버전)\",\"sectionIds\":[111]}]"),
                // 이 프로젝트에 지금 연결되지 않은(삭제된) 자료의 제안 — 읽지 않는다.
                proposal(53, 99, "h99", "[{\"op\":\"ADD\",\"tempId\":\"n1\",\"title\":\"사라진 자료\",\"sectionIds\":[101]}]")));
        f.catalogService.setProposedTopicIndex(new ProposedTopicIndex(proposals));
        f.selectionAnswer = prompt -> {
            assertThat(prompt).contains("자동 분석 제안(승인 전) · 스택").contains("스택의 정의").contains("스택 연습");
            assertThat(prompt).contains("토픽에 연결되지 않은 자료 · ch02.pdf").doesNotContain("큐(옛 버전)")
                    .doesNotContain("사라진 자료");
            // 제안 노드는 학습 항목이 아니다 — 학습 항목(t) 핸들을 받지 않는다.
            assertThat(prompt).doesNotContainPattern("\\bt\\d+ 스택");
            return selection(List.of(PlanSelectionFixture.handleOf(prompt, "스택의 정의")), List.of(), List.of());
        };
        f.planAnswer = prompt -> PlanSelectionFixture.planWithOneItem(refOf(prompt, "스택의 정의"));

        Generated generated = f.generate(null);

        // 구조 승인 없이도 계획이 만들어진다. 그리고 제안 저장소에는 읽기 하나만 일어났다(쓰기 없음).
        assertThat(generated.items()).hasSize(1);
        assertThat(generated.items().get(0).topicId()).isNull();
        verify(proposals).findOpenByCourseId(1L, USER);
        verifyNoMoreInteractions(proposals);
    }

    @Test
    void T04_같은_자리의_같은_제목_제안은_한_묶음으로_합치고_부모가_다르면_합치지_않는다() {
        TopicChangeProposalMapper proposals = mock(TopicChangeProposalMapper.class);
        when(proposals.findOpenByCourseId(1L, USER)).thenReturn(List.of(
                proposal(51, 10, "h10", "[{\"op\":\"ADD\",\"tempId\":\"a\",\"title\":\"스택\",\"sectionIds\":[1],"
                        + "\"children\":[{\"op\":\"ADD\",\"tempId\":\"a1\",\"title\":\"개요\",\"sectionIds\":[2]}]}]"),
                proposal(52, 11, "h11", "[{\"op\":\"ADD\",\"tempId\":\"b\",\"title\":\" 스택 \",\"sectionIds\":[3]},"
                        + "{\"op\":\"ADD\",\"tempId\":\"c\",\"title\":\"개요\",\"sectionIds\":[4]}]")));
        var materials = new java.util.HashMap<Long, com.jungwoo.project.memo.material.domain.CourseMaterial>();
        PlanSelectionFixture f = new PlanSelectionFixture();
        materials.put(10L, f.material(10, "a.pdf", 1L));
        materials.put(11L, f.material(11, "b.pdf", 1L));

        ProposedTopicIndex.Index index = new ProposedTopicIndex(proposals).forCourse(USER, 1L, materials);

        assertThat(index.nodes()).extracting(ProposedTopicIndex.Node::title).containsExactly("스택", "개요", "개요");
        ProposedTopicIndex.Node stack = index.nodes().get(0);
        assertThat(stack.sectionIds()).containsExactly(1L, 3L);
        assertThat(stack.proposalIds()).containsExactly(51L, 52L);
        assertThat(stack.nodeId()).isEqualTo("p51:a");
        // "개요"는 하나는 스택 아래, 하나는 최상위다 — 제목이 같다고 합치지 않는다.
        assertThat(index.nodes().get(1).parentNodeId()).isEqualTo("p51:a");
        assertThat(index.nodes().get(2).parentNodeId()).isNull();
    }

    @Test
    void T07_사용자가_말한_시간은_강도_예산보다_우선이고_넘치는_항목은_이유와_함께_미룬_범위로_간다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "스택 개념", roles("CONCEPT"), 1, "원문-스택");
        PlanBriefItem time = new PlanBriefItem(1, "TIME_BUDGET", "오늘 한 시간만 (쓸 수 있는 시간 60분 · 계획 전체)",
                PlanBriefItem.SPEAKER_USER, true, false, false, PlanBriefItem.SCOPE_THIS_DRAFT, 10L, 10L, null, null, null,
                null, 1, List.of(), LocalDateTime.of(2026, 9, 13, 9, 0));
        when(f.briefService.load(anyLong(), any())).thenReturn(new PlanBriefService.View(5L, 77L, 3, List.of(time), null));
        f.selectionAnswer = prompt -> selection(List.of(PlanSelectionFixture.handleOf(prompt, "스택 개념")), List.of(), List.of());
        f.planAnswer = prompt -> {
            String ref = refOf(prompt, "스택 개념");
            return "{\"title\":\"t\",\"items\":[" + item("스택 핵심 회수", ref, 40, "MUST") + ","
                    + item("스택 구현 연습", ref, 40, "SHOULD") + "," + item("스택 응용 읽기", ref, 30, "OPTIONAL") + "]}";
        };

        Generated generated = f.generate(f.spec("스택 위주로", new com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Origin(
                77L, null, "req-1", null, null)), com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Options.none());

        assertThat(f.planPrompt()).contains("[사용자가 말한 시간]").contains("60분");
        assertThat(generated.targetMinutes()).isEqualTo(60);
        assertThat(generated.items()).extracting(i -> i.title()).containsExactly("스택 핵심 회수");
        assertThat(generated.strategy().deferred()).extracting(d -> d.title())
                .contains("스택 구현 연습", "스택 응용 읽기");
        assertThat(generated.strategy().deferred()).anyMatch(d -> d.reason().contains("60분"));
    }

    @Test
    void T07_AI가_제안만_한_시간은_사용자의_제약이_아니고_하루_기준은_기간으로_환산한다() {
        PlanBriefItem aiOnly = new PlanBriefItem(1, "TIME_BUDGET", "하루 30분이면 어때요 (쓸 수 있는 시간 30분 · 하루)",
                PlanBriefItem.SPEAKER_ASSISTANT, false, false, false, PlanBriefItem.SCOPE_THIS_DRAFT, 1L, null, null, null,
                null, null, 1, List.of(), LocalDateTime.now());
        PlanBriefItem said = new PlanBriefItem(2, "TIME_BUDGET", "평일엔 하루 15분 (쓸 수 있는 시간 15분 · 하루)",
                PlanBriefItem.SPEAKER_USER, true, false, false, PlanBriefItem.SCOPE_THIS_DRAFT, 1L, 1L, null, null, null,
                null, 1, List.of(), LocalDateTime.now());

        assertThat(PlanBriefService.timeBudgetOf(List.of(aiOnly))).isNull();
        PlanBriefService.TimeBudget budget = PlanBriefService.timeBudgetOf(List.of(aiOnly, said));
        assertThat(budget.minutes()).isEqualTo(15);
        assertThat(budget.totalFor(5)).isEqualTo(75);
    }

    @Test
    void T13_일정_조회가_실패하면_일정_없음으로_가정하지_않고_생성이_실패한다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        when(f.availability.estimate(anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("routines 조회 실패"));

        assertThatThrownBy(() -> f.generate(null)).isInstanceOf(IllegalStateException.class);
        // 조회 오류가 "하루 14시간 가능" 가정으로 바뀌어 모델 호출까지 가지 않았다.
        assertThat(f.calls).isEmpty();
        assertThat(START).isBefore(END);
    }

    private static String item(String title, String ref, int minutes, String priority) {
        return "{\"title\":\"" + title + "\",\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":" + minutes
                + ",\"priority\":\"" + priority + "\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\"" + ref + "\"]}";
    }
}
