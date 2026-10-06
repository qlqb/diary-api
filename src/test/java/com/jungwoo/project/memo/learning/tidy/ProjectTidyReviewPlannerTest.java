package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큰 프로젝트에서도 뒤쪽 자료가 판단에서 빠지지 않는다.
 *
 * <p>예전 방식은 글자 예산을 <b>입력 순서대로</b> 채웠다. 자료가 많으면 마지막 자료의 구간은
 * 매번 잘렸고, 거기 있는 중복·연결 대상은 판단에 한 번도 들어가지 못했다 — 순서 때문에 계속.
 * 여기서는 (1) 모든 구간이 목록 수준에는 반드시 오르고, (2) 자세히 읽을 것은 모델이 전체 목록을
 * 보고 중요도로 고르며, (3) 목록을 나눠 물었으면 조각을 번갈아 채워 첫 조각이 예산을 독차지하지
 * 않는지를 본다. 모델 호출은 가짜다 — 이 테스트는 "무엇이 모델에 실리는가"만 본다.
 */
class ProjectTidyReviewPlannerTest {

    private static final long LATE = 99L;

    @Test
    void 예전_방식은_입력_순서로_잘라_마지막_자료를_매번_버렸다() {
        // 이 테스트는 고친 것이 아니라 고치기 전의 모양을 고정한다. 아래 테스트와 나란히 봐야 한다.
        ProjectTidyInputBuilder builder = new ProjectTidyInputBuilder(null, null, null, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "sectionCharBudget", 3000);
        List<MaterialSection> index = bigProject();

        List<MaterialSection> chosen = builder.chooseWithinBudget(index, java.util.Set.of());

        assertThat(chosen).extracting(MaterialSection::getMaterialId).doesNotContain(LATE);
    }

    @Test
    void 모든_구간이_목록에는_오르고_뒤쪽_자료도_골라_자세히_읽힌다() {
        ProjectTidyReviewPlanner planner = planner(200_000, 3000, 3);
        List<MaterialSection> index = bigProject();
        List<Long> lateSections = index.stream().filter(s -> s.getMaterialId() == LATE)
                .map(MaterialSection::getSectionId).toList();

        // 모델은 뒤쪽 자료의 구간이 기존 항목과 겹친다고 보고 그것부터 고른다.
        ProjectTidyReviewPlanner.Review review = planner.plan(List.of(topic(1, "스택")), materials(index), index,
                (prompt, max) -> {
                    assertThat(prompt).contains("@M" + LATE);
                    return lateSections;
                });

        assertThat(review.listedSectionIds()).hasSize(index.size());
        assertThat(review.detailSectionIds()).containsAll(lateSections);
        assertThat(review.unlistedMaterialIds()).isEmpty();
        assertThat(review.partial()).isFalse();
        assertThat(review.modelCalls()).isEqualTo(2);
    }

    @Test
    void 목록이_한_번에_안_들어가면_나눠_묻고_번갈아_채운다() {
        // 목록 예산을 작게 둬 자료마다 한 조각이 되게 한다.
        ProjectTidyReviewPlanner planner = planner(3500, 2500, 5);
        List<MaterialSection> index = bigProject();
        AtomicInteger calls = new AtomicInteger();

        ProjectTidyReviewPlanner.Review review = planner.plan(List.of(), materials(index), index,
                (prompt, max) -> {
                    calls.incrementAndGet();
                    // 조각마다 그 조각의 구간을 전부 "중요하다"고 답한다.
                    return index.stream().filter(s -> prompt.contains("S" + s.getSectionId() + " "))
                            .map(MaterialSection::getSectionId).toList();
                });

        assertThat(calls.get()).isEqualTo(2);
        /*
         * 예산이 첫 조각에서 동나지 않는다 — 두 조각이 번갈아 채운다. (조각 <안에서> 무엇을 먼저
         * 읽을지는 모델의 중요도 순서다. 이 가짜 모델은 입력 순서로 답하므로 그 부분은 여기서 보지
         * 않는다. 뒤쪽 자료를 중요하다고 고르면 읽힌다는 것은 위 테스트가 본다.)
         */
        List<Long> detailMaterials = new ArrayList<>();
        review.detailSectionIds().forEach(id -> index.stream().filter(s -> s.getSectionId().equals(id))
                .findFirst().ifPresent(s -> detailMaterials.add(s.getMaterialId())));
        long fromFirst = detailMaterials.stream().filter(m -> m < 17).count();
        long fromSecond = detailMaterials.size() - fromFirst;
        assertThat(fromSecond).as("둘째 조각도 상세 예산을 받는다").isGreaterThan(0);
        assertThat(Math.abs(fromFirst - fromSecond)).isLessThanOrEqualTo(1);
    }

    @Test
    void 고르기_호출_한도를_넘는_자료는_목록에서도_못_봤다고_기록한다() {
        ProjectTidyReviewPlanner planner = planner(1500, 2500, 2);
        List<MaterialSection> index = bigProject();

        ProjectTidyReviewPlanner.Review review = planner.plan(List.of(), materials(index), index,
                (prompt, max) -> List.of());

        assertThat(review.selectCalls()).isEqualTo(2);
        assertThat(review.unlistedMaterialIds()).isNotEmpty();
        assertThat(review.partial()).isTrue();
        assertThat(review.listedSectionIds()).hasSizeLessThan(index.size());
    }

    @Test
    void 모델이_지어낸_번호는_상세로_싣지_않는다() {
        ProjectTidyReviewPlanner planner = planner(200_000, 3000, 3);
        List<MaterialSection> index = bigProject();

        ProjectTidyReviewPlanner.Review review = planner.plan(List.of(), materials(index), index,
                (prompt, max) -> List.of(123456789L, index.get(0).getSectionId()));

        assertThat(review.detailSectionIds()).containsExactly(index.get(0).getSectionId());
    }

    @Test
    void 작은_프로젝트는_고르지_않고_전부_자세히_본다() {
        ProjectTidyReviewPlanner planner = planner(60_000, 40_000, 3);
        List<MaterialSection> index = List.of(section(1, 10, "스택 정의"), section(2, 11, "3장 스택"));

        ProjectTidyReviewPlanner.Review review = planner.plan(List.of(), materials(index), index,
                (prompt, max) -> {
                    throw new AssertionError("다 들어가면 고르기 호출을 하지 않는다");
                });

        assertThat(review.detailSectionIds()).containsExactly(1L, 2L);
        assertThat(review.modelCalls()).isEqualTo(1);
    }

    @Test
    void 목록으로_본_것과_자세히_읽은_것을_따로_기록하고_못_본_자료는_제외로_옮긴다() {
        ProjectTidyInputBuilder builder = new ProjectTidyInputBuilder(null, null, null, null, null, null, null);
        List<MaterialSection> index = List.of(section(1, 10, "a"), section(2, 10, "b"), section(3, 99, "c"));
        ProjectTidyScope scope = new ProjectTidyScope(7L, 0L, 0, 0,
                List.of(new ProjectTidyScope.Member(10L, "앞.pdf", "h", 1, 2, 0, 0),
                        new ProjectTidyScope.Member(99L, "뒤.pdf", "h", 1, 1, 0, 0)),
                List.of(), false, 3, 0, 0, 0);
        ProjectTidyInputBuilder.Input input = new ProjectTidyInputBuilder.Input(null, List.of(), List.of(), index,
                Map.of(), List.of(), scope);
        // 자료 10은 두 구간 모두 목록으로 보고 하나만 자세히 읽었다. 자료 99는 목록에도 오르지 못했다.
        ProjectTidyReviewPlanner.Review review = new ProjectTidyReviewPlanner.Review(
                new java.util.LinkedHashSet<>(List.of(1L, 2L)), List.of(1L), java.util.Set.of(99L), 2, false);

        ProjectTidyScope out = builder.finalizeScope(input, review).scope();

        assertThat(out.sectionsTotal()).isEqualTo(3);
        assertThat(out.sectionsListed()).isEqualTo(2);
        assertThat(out.sectionsReviewed()).isEqualTo(1);
        assertThat(out.modelCalls()).isEqualTo(3);
        assertThat(out.truncated()).as("목록에서도 못 본 자료가 있으면 부분 정리다").isTrue();
        assertThat(out.reviewed()).singleElement().satisfies(m -> {
            assertThat(m.materialId()).isEqualTo(10L);
            assertThat(m.listedCount()).isEqualTo(2);
            assertThat(m.reviewedCount()).isEqualTo(1);
        });
        assertThat(out.excluded()).extracting(ProjectTidyScope.Excluded::materialId).containsExactly(99L);
    }

    @Test
    void 전부_목록으로_봤으면_상세가_일부여도_부분_정리가_아니다() {
        ProjectTidyInputBuilder builder = new ProjectTidyInputBuilder(null, null, null, null, null, null, null);
        List<MaterialSection> index = List.of(section(1, 10, "a"), section(2, 10, "b"));
        ProjectTidyScope scope = new ProjectTidyScope(7L, 0L, 0, 0,
                List.of(new ProjectTidyScope.Member(10L, "앞.pdf", "h", 1, 2, 0, 0)),
                List.of(), false, 2, 0, 0, 0);
        ProjectTidyInputBuilder.Input input = new ProjectTidyInputBuilder.Input(null, List.of(), List.of(), index,
                Map.of(), List.of(), scope);

        ProjectTidyScope out = builder.finalizeScope(input, new ProjectTidyReviewPlanner.Review(
                new java.util.LinkedHashSet<>(List.of(1L, 2L)), List.of(2L), java.util.Set.of(), 1, false)).scope();

        // 모든 구간을 목록으로 봤고, 자세히 읽을 것은 모델이 골랐다. 빠뜨린 것이 없다.
        assertThat(out.truncated()).isFalse();
        assertThat(out.sectionsListed()).isEqualTo(2);
        assertThat(out.sectionsReviewed()).isEqualTo(1);
    }

    // ===== 준비 도구 =====

    /** 자료 10개, 자료마다 구간 12개. 마지막 자료(id 99)가 뒤쪽이다. */
    private static List<MaterialSection> bigProject() {
        List<MaterialSection> out = new ArrayList<>();
        long sectionId = 1000;
        for (int m = 0; m < 10; m++) {
            long materialId = m == 9 ? LATE : 10 + m;
            for (int i = 0; i < 12; i++) {
                MaterialSection s = section(sectionId++, materialId, "자료" + materialId + " 구간 " + i);
                s.setExcerpt("본문 발췌 ".repeat(20));
                out.add(s);
            }
        }
        return out;
    }

    private static MaterialSection section(long id, long materialId, String title) {
        MaterialSection s = new MaterialSection();
        s.setSectionId(id);
        s.setMaterialId(materialId);
        s.setDisplayTitle(title);
        s.setRolesJson("[\"CONCEPT\"]");
        s.setUnitType(com.jungwoo.project.memo.material.domain.TextUnitType.PDF_PAGE);
        s.setUnitStart(1);
        s.setUnitEnd(1);
        return s;
    }

    private static Map<Long, CourseMaterial> materials(List<MaterialSection> index) {
        Map<Long, CourseMaterial> out = new LinkedHashMap<>();
        index.forEach(s -> out.computeIfAbsent(s.getMaterialId(), id -> CourseMaterial.builder()
                .materialId(id).originalFilename("자료" + id + ".pdf").build()));
        return out;
    }

    private static CourseTopic topic(long id, String title) {
        return CourseTopic.builder().topicId(id).title(title).orderIndex(0).build();
    }

    private static ProjectTidyReviewPlanner planner(int listBudget, int detailBudget, int maxSelect) {
        ProjectTidyReviewPlanner p = new ProjectTidyReviewPlanner();
        p.listCharBudget = listBudget;
        p.detailCharBudget = detailBudget;
        p.maxSelectCalls = maxSelect;
        return p;
    }
}
