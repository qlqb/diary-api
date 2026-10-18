package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.plan.scope.RequestScope;
import com.jungwoo.project.memo.plan.selection.PlanMaterialRetriever;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 2단계: 이번 요청에 유효한 맥락만 싣고, 입력 예산을 줄일 때 범위의 부분마다 원문 하나는 남긴다. */
class PlanScopeInputsTest {

    private static UserContext context(Long courseId, LocalDate from, LocalDate to, String content) {
        UserContext c = new UserContext();
        c.setCourseId(courseId);
        c.setScopeStart(from);
        c.setScopeEnd(to);
        c.setContent(content);
        return c;
    }

    @Test
    void 다른_프로젝트_한정과_기간이_겹치지_않는_맥락은_빼고_전역_맥락은_둔다() {
        LocalDate start = LocalDate.of(2026, 10, 18);
        List<UserContext> kept = PeriodPlanDraftGenerator.relevantContexts(List.of(
                context(934L, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 20), "자료구조 개념 먼저"),
                context(null, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3), "10/03 알바 안 감"),
                context(null, null, null, "금요일 밤은 비움"),
                context(936L, null, null, "빅데이터는 코드 재현이 어렵다")), Set.of(936L, 937L), start, start);

        assertThat(kept).extracting(UserContext::getContent).containsExactly("금요일 밤은 비움", "빅데이터는 코드 재현이 어렵다");
    }

    private static PlanMaterialRetriever.Retrieved retrieved(long sectionId, long materialId) {
        MaterialSection section = new MaterialSection();
        section.setSectionId(sectionId);
        CourseMaterial material = CourseMaterial.builder().materialId(materialId).build();
        return new PlanMaterialRetriever.Retrieved(new PlanMaterialRetriever.Target("m" + sectionId, sectionId, null, 1L,
                null, null), section, material, List.of(), PlanMaterialRetriever.Outcome.FULL);
    }

    @Test
    void 확인된_범위의_부분마다_그_부분만의_자료에서_원문_하나를_남긴다() {
        RequestScope net = new RequestScope("S1", 937L, "네트워크", RequestScope.OPTIONAL, null, null, "q", "q", true, List.of(
                new RequestScope.Part("S1.P1", "6주차", List.of(460L), false, false),
                new RequestScope.Part("S1.P2", "7주차", List.of(459L), false, false)));
        RequestScope unverified = new RequestScope("S2", 937L, "네트워크", RequestScope.REQUIRED, null, null, "q", null, false,
                List.of(new RequestScope.Part("S2.P1", "2주차", List.of(245L), false, false)));
        PeriodPlanDraftGenerator.PlanInputs inputs = new PeriodPlanDraftGenerator.PlanInputs(List.of(), null,
                List.of(retrieved(41L, 459L), retrieved(42L, 459L), retrieved(31L, 460L), retrieved(21L, 245L)),
                new java.util.LinkedHashSet<>(), 2400, List.of(), null, null, 0, List.of(), List.of(), null,
                List.of(net, unverified));

        // 6주차·7주차의 첫 구간만 남기고, 근거 미확인 범위(2주차)는 보호하지 않는다.
        assertThat(PeriodPlanDraftGenerator.scopeAnchors(inputs)).containsExactlyInAnyOrder(41L, 31L);
    }
    @Test
    void 근거_미확인_범위가_같은_자료를_가리켜도_확인된_부분의_보호를_막지_않는다() {
        RequestScope verified = new RequestScope("S1", 937L, "네트워크", RequestScope.REQUIRED, null, null, "q", "q", true,
                List.of(new RequestScope.Part("S1.P1", "6주차", List.of(460L), false, false)));
        RequestScope unverified = new RequestScope("S2", 937L, "네트워크", RequestScope.REQUIRED, null, null, "q", null, false,
                List.of(new RequestScope.Part("S2.P1", "6주차 다시", List.of(460L), false, false)));
        PeriodPlanDraftGenerator.PlanInputs inputs = new PeriodPlanDraftGenerator.PlanInputs(List.of(), null,
                List.of(retrieved(31L, 460L)), new java.util.LinkedHashSet<>(), 2400, List.of(), null, null, 0, List.of(),
                List.of(), null, List.of(verified, unverified));

        assertThat(PeriodPlanDraftGenerator.scopeAnchors(inputs)).containsExactly(31L);
        assertThat(com.jungwoo.project.memo.plan.scope.ScopeCoverage.partsPerMaterial(List.of(verified, unverified)))
                .containsEntry(460L, 1);
    }
}
