package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.learning.structure.ProposedTopicIndex;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyMapper;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.learning.tidy.domain.TidyProposalStatus;
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

    private static ProjectTidyProposal tidyProposal(long id, String ops) {
        return ProjectTidyProposal.builder().proposalId(id).userId(USER).courseId(1L)
                .status(TidyProposalStatus.PROPOSED).revision(1L).baseTreeVersion(0L)
                .opsJson(ops).summaryJson("{}").scopeJson("{}").build();
    }

    /** 이 정리안이 근거로 삼은 자료. hash가 지금 자료와 다르면 그 구간은 색인에서 빠진다. */
    private static ProjectTidyProposalMaterial evidence(long proposalId, long materialId, String hash) {
        return ProjectTidyProposalMaterial.builder().proposalId(proposalId).userId(USER)
                .materialId(materialId).fileHash(hash).analysisVersion(1)
                .sectionCount(1).reviewedCount(1).included(true).build();
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
        /*
         * (2026-09-21) 색인의 원본이 프로젝트 단위 정리안 하나로 바뀌었다. 정리안은 자료 셋을 함께
         * 보고 만들어졌지만, 그 사이 ch02.pdf가 다시 분석돼 해시가 달라졌고 99번 자료는 연결이
         * 끊겼다 — 두 자료의 구간은 색인에서 빠진다.
         */
        ProjectTidyMapper tidy = mock(ProjectTidyMapper.class);
        when(tidy.findOpenProposalByCourse(1L, USER)).thenReturn(tidyProposal(51,
                "[{\"op\":\"ADD\",\"tempId\":\"n1\",\"changeId\":\"c1\",\"title\":\"스택\",\"sectionIds\":[101,102]},"
                + "{\"op\":\"ADD\",\"tempId\":\"n2\",\"changeId\":\"c2\",\"title\":\"큐(옛 버전)\",\"sectionIds\":[111]},"
                + "{\"op\":\"ADD\",\"tempId\":\"n3\",\"changeId\":\"c3\",\"title\":\"사라진 자료\",\"sectionIds\":[901]}]"));
        when(tidy.findProposalMaterials(51L, USER)).thenReturn(List.of(
                evidence(51, 10, "h10"),
                evidence(51, 11, "OLD-HASH"),
                evidence(51, 99, "h99")));
        f.catalogService.setProposedTopicIndex(new ProposedTopicIndex(tidy, f.sectionMapper));
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
        verify(tidy).findOpenProposalByCourse(1L, USER);
        verify(tidy).findProposalMaterials(51L, USER);
        verifyNoMoreInteractions(tidy);
    }

    @Test
    void T04_한_노드에_여러_자료의_구간이_함께_붙고_새_항목의_부모도_이어진다() {
        /*
         * 예전에는 자료마다 변경안이 따로 있어서, 같은 제목의 제안 둘을 이 색인이 합쳐야 했다.
         * 이제는 정리안 하나가 자료들을 함께 보고 나오므로 합칠 것이 없다 — 한 노드의 근거 구간이
         * 애초에 여러 자료에 걸쳐 있다. 이 테스트가 보는 것이 그 차이다.
         */
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.material(11, "b.pdf", 1L);
        f.section(1, 10, "스택 설명", roles("CONCEPT"), 1, "원문-1");
        f.section(2, 10, "스택 개요", roles("CONCEPT"), 2, "원문-2");
        f.section(3, 11, "교재의 스택", roles("CONCEPT"), 1, "원문-3");

        ProjectTidyMapper tidy = mock(ProjectTidyMapper.class);
        when(tidy.findOpenProposalByCourse(1L, USER)).thenReturn(tidyProposal(51,
                "[{\"op\":\"ADD\",\"tempId\":\"a\",\"changeId\":\"c1\",\"title\":\"스택\",\"sectionIds\":[1,3],"
                + "\"children\":[{\"op\":\"ADD\",\"tempId\":\"a1\",\"title\":\"개요\",\"sectionIds\":[2]}]}]"));
        when(tidy.findProposalMaterials(51L, USER)).thenReturn(List.of(
                evidence(51, 10, "h10"), evidence(51, 11, "h11")));
        var materials = new java.util.HashMap<Long, com.jungwoo.project.memo.material.domain.CourseMaterial>();
        materials.put(10L, f.materials.get(10L));
        materials.put(11L, f.materials.get(11L));

        ProposedTopicIndex.Index index = new ProposedTopicIndex(tidy, f.sectionMapper)
                .forCourse(USER, 1L, materials);

        assertThat(index.nodes()).extracting(ProposedTopicIndex.Node::title).containsExactly("스택", "개요");
        ProposedTopicIndex.Node stack = index.nodes().get(0);
        assertThat(stack.sectionIds()).containsExactly(1L, 3L);
        // 한 학습 항목에 강의 자료와 교재가 함께 붙는 것 — 프로젝트 단위 정리의 요점이다.
        assertThat(stack.materialIds()).containsExactly(10L, 11L);
        assertThat(stack.nodeId()).isEqualTo("p51:c1");
        assertThat(index.nodes().get(1).parentNodeId()).isEqualTo("p51:c1");
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
