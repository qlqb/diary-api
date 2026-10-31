package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.plan.domain.ProjectOutcome;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.Disposition;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.MaterialState;
import com.jungwoo.project.memo.plan.generation.ProjectOutcomeResolver.Facts;
import com.jungwoo.project.memo.plan.generation.ProjectOutcomeResolver.ItemTotals;
import com.jungwoo.project.memo.plan.generation.ProjectOutcomeResolver.ModelOut;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** handoff §14.3 — 대상 프로젝트는 모두 정확히 한 번, 보지 못한 것을 중요도 판단으로 포장하지 않는다(T02·T03). */
class ProjectOutcomeResolverTest {

    private static Facts facts(long id, int materials, int pending, int candidates, Integer shown, int selected,
                               int delivered, int failed, boolean outlineOnlyByChoice) {
        return new Facts(id, "P" + id, materials, pending, candidates, shown, selected, delivered, failed,
                delivered > 0 ? List.of(id * 10) : List.of(), null, null, outlineOnlyByChoice);
    }

    @Test
    void 한도_때문에_한_줄도_못_본_프로젝트를_모델이_제외라고_해도_미검토다() {
        List<ProjectOutcome> out = ProjectOutcomeResolver.resolve(
                List.of(facts(1, 3, 0, 89, 0, 0, 0, 0, false)),
                List.of(new ModelOut(1L, "EXCLUDED", "우선순위가 낮아 다음 기간으로", null)), Map.of());

        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.materialState()).isEqualTo(MaterialState.NOT_LISTED);
            assertThat(o.disposition()).isEqualTo(Disposition.NOT_REVIEWED);
            assertThat(o.decidedBy()).isEqualTo("SERVER");
            assertThat(o.reason()).contains("후보 89개").doesNotContain("우선순위");
            assertThat(o.nextAction()).isEqualTo(ProjectOutcome.NextAction.NARROW_SCOPE);
        });
    }

    @Test
    void 조회_실패와_분석_대기는_각각_다른_상태와_다음_행동을_갖는다() {
        List<ProjectOutcome> out = ProjectOutcomeResolver.resolve(List.of(
                        facts(1, 2, 0, 10, 10, 2, 0, 2, false),
                        facts(2, 4, 4, 0, 0, 0, 0, 0, false),
                        facts(3, 1, 0, 0, 0, 0, 0, 0, false)),
                List.of(new ModelOut(1L, "UNDECIDED", null, null), new ModelOut(2L, "EXCLUDED", "다음에", null),
                        new ModelOut(3L, "UNDECIDED", "쓸 내용이 없음", null)), Map.of());

        assertThat(out).extracting(ProjectOutcome::materialState).containsExactly(MaterialState.RETRIEVAL_FAILED,
                MaterialState.ANALYSIS_PENDING, MaterialState.NO_RELEVANT_CONTENT);
        assertThat(out).extracting(ProjectOutcome::disposition).containsExactly(Disposition.NOT_REVIEWED,
                Disposition.NOT_REVIEWED, Disposition.UNDECIDED);
        assertThat(out).extracting(ProjectOutcome::nextAction).containsExactly(ProjectOutcome.NextAction.RETRY_ANALYSIS,
                ProjectOutcome.NextAction.WAIT_ANALYSIS, ProjectOutcome.NextAction.ANSWER_QUESTION);
    }

    @Test
    void 항목이_있으면_포함이고_포함이라_했는데_항목이_없으면_보류로_낮춘다() {
        List<ProjectOutcome> out = ProjectOutcomeResolver.resolve(List.of(
                        facts(1, 1, 0, 5, 5, 1, 1, 0, false), facts(2, 1, 0, 5, 5, 1, 1, 0, false)),
                List.of(new ModelOut(1L, "EXCLUDED", "모델이 뺐다고 함", null), new ModelOut(2L, "INCLUDED", "다룸", null)),
                Map.of(1L, new ItemTotals(2, 50)));

        assertThat(out.get(0).disposition()).isEqualTo(Disposition.INCLUDED);
        assertThat(out.get(0).itemMinutes()).isEqualTo(50);
        assertThat(out.get(1).disposition()).isEqualTo(Disposition.UNDECIDED);
        assertThat(out.get(1).decidedBy()).isEqualTo("SERVER");
    }

    @Test
    void 중복과_범위_밖_id는_버리고_모든_대상이_정확히_한_번_나온다() {
        List<ProjectOutcome> out = ProjectOutcomeResolver.resolve(List.of(
                        facts(1, 1, 0, 5, 5, 0, 0, 0, false), facts(2, 1, 0, 5, 5, 0, 0, 0, false)),
                List.of(new ModelOut(1L, "EXCLUDED", "첫 번째", null), new ModelOut(1L, "UNDECIDED", "두 번째", null),
                        new ModelOut(77L, "INCLUDED", "남의 것", null)), Map.of());

        assertThat(out).extracting(ProjectOutcome::courseId).containsExactly(1L, 2L);
        assertThat(out.get(0).reason()).isEqualTo("첫 번째");
        assertThat(out.get(1).disposition()).isEqualTo(Disposition.NOT_REVIEWED);
    }

    @Test
    void 선택을_재사용한_회차는_목록_전달_수를_모른다고_적는다() {
        List<ProjectOutcome> out = ProjectOutcomeResolver.resolve(List.of(facts(1, 1, 0, 5, null, 1, 1, 0, false)),
                List.of(), Map.of(1L, new ItemTotals(1, 30)));

        assertThat(out.get(0).shown()).isNull();
        assertThat(out.get(0).materialState()).isEqualTo(MaterialState.TEXT_DELIVERED);
    }
}
