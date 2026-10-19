package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
import com.jungwoo.project.memo.ai.state.ProjectStateService;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.plan.selection.PlanCatalogText;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 막혔던·도움받아 해결한 단원은 계획 판단에 반드시 남는다 — 목록에 없으면 계획이 그 단원을 가리킬 수 없다(실호출에서 Unit 1만
 * 나온 원인). 확인된 수업 진도가 있으면 기록 기준 "첫 미학습"을 출발점으로 붙이지 않는다.
 */
class StateFocusTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 20, 0);

    private static PlanMaterialContextService.TopicLine topic(long id, String title, int seq) {
        return new PlanMaterialContextService.TopicLine(id, null, title, "교재 p." + seq * 6, null, null, 0,
                TopicProgressStatus.NOT_STARTED, null, null, false).withTocSeq(seq);
    }

    private static ProjectStateService.Fact fact(long id, FactKind kind, Long topicId, ContextEvidenceType type, String help) {
        return new ProjectStateService.Fact(id, kind, null, "x", topicId, null, null, type, ContextSourceType.CONSULT_AUTO,
                help, AT, AT, "ACTIVE", null);
    }

    private static ProjectStateService.State state(List<ProjectStateService.Fact> facts) {
        return new ProjectStateService.State(940L, "영어회화", null, facts, List.of(), List.of(), "fp");
    }

    @Test
    void 막힌_곳과_도움받아_해결한_단원은_표시되고_필수로_남으며_추정은_표시하지_않는다() {
        PlanMaterialContextService.CourseCatalog catalog = new PlanMaterialContextService.CourseCatalog(940L, "영어회화",
                List.of(topic(501, "Unit 1 What's your name?", 1), topic(503, "Unit 3 I have to …", 3),
                        topic(504, "Unit 4 Did you …", 4), topic(505, "Unit 5 …", 5)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 4);

        PlanMaterialContextService.CourseCatalog marked = PeriodPlanDraftGenerator.withStateFocus(catalog, state(List.of(
                fact(1, FactKind.RESOLVED, 503L, ContextEvidenceType.SELF_REPORT, "GUIDED"),
                fact(2, FactKind.DIFFICULTY, 504L, ContextEvidenceType.STATED, null),
                fact(3, FactKind.DIFFICULTY, 505L, ContextEvidenceType.INFERRED, null))));

        assertThat(marked.requiredTopics()).extracting(PlanMaterialContextService.TopicLine::topicId)
                .containsExactly(503L, 504L);
        PlanMaterialContextService.TopicLine unit3 = marked.requiredTopics().get(0);
        assertThat(PlanCatalogText.topicFlags(unit3)).contains("도움받아 해결(10/5) — 혼자 다시 해 보기 좋은 후보");
        assertThat(PlanCatalogText.topicFlags(marked.requiredTopics().get(1))).contains("막힌 곳(10/5) — 먼저 볼 후보");
        assertThat(PlanCatalogText.topicTitle(unit3)).contains("교재 목차 3번째, 제목만 확인");
    }

    @Test
    void 확인된_진도가_있을_때만_첫_미학습_기준점을_끈다() {
        assertThat(PeriodPlanDraftGenerator.hasConfirmedProgress(null)).isFalse();
        assertThat(PeriodPlanDraftGenerator.hasConfirmedProgress(state(List.of(
                fact(1, FactKind.PROGRESS, null, ContextEvidenceType.INFERRED, null))))).isFalse();
        assertThat(PeriodPlanDraftGenerator.hasConfirmedProgress(state(List.of(
                fact(1, FactKind.PROGRESS, null, ContextEvidenceType.STATED, null))))).isTrue();
    }
}
