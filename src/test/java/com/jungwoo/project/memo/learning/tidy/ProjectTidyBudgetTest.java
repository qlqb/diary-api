package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 입력 한도에 걸렸을 때 무엇을 남기는가.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>예산을 넘으면 <b>자른다</b>. 잘라도 아직 학습 항목과 이어지지 않은 자료가 먼저 남는다 —
 *       바뀔 것이 가장 많은 쪽이다.</li>
 *   <li>제출 단서가 있는 구간이 그다음이다. 과제로 이어지기 때문이다.</li>
 *   <li>고른 결과는 <b>원래 순서</b>로 돌려준다. 모델이 읽기 좋게, 그리고 "어느 자료에서 얼마나
 *       잘랐는지"를 셀 수 있게.</li>
 *   <li>예산이 아무리 작아도 최소 하나는 남는다. 빈 입력으로 모델을 부르지 않는다.</li>
 * </ul>
 */
class ProjectTidyBudgetTest {

    private ProjectTidyInputBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ProjectTidyInputBuilder(
                mock(com.jungwoo.project.memo.learning.CourseTopicMapper.class),
                mock(com.jungwoo.project.memo.learning.TopicMaterialLinkMapper.class),
                mock(com.jungwoo.project.memo.material.MaterialLinkMapper.class),
                mock(com.jungwoo.project.memo.material.CourseMaterialMapper.class),
                mock(com.jungwoo.project.memo.material.MaterialSectionMapper.class),
                mock(com.jungwoo.project.memo.assignment.CourseAssignmentMapper.class),
                mock(com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService.class));
    }

    private static MaterialSection section(long id, long materialId, String title, boolean cue) {
        return MaterialSection.builder()
                .sectionId(id).materialId(materialId).fileHash("h").analysisVersion(1).chunkIndex(0)
                .unitType(TextUnitType.PDF_PAGE).unitStart(1).unitEnd(1)
                .displayTitle(title).rolesJson("[\"CONCEPT\"]").assignmentCue(cue)
                .taskText("x".repeat(200)).dedupeKey("k" + id).status("ACTIVE")
                .build();
    }

    @Test
    void 예산_안에_다_들어가면_전부_그대로_돌려준다() {
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 100_000);
        List<MaterialSection> all = List.of(
                section(1, 10, "가", false), section(2, 10, "나", false), section(3, 11, "다", false));

        assertThat(builder.chooseWithinBudget(all, Set.of()))
                .extracting(MaterialSection::getSectionId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 예산을_넘으면_아직_이어지지_않은_자료의_구간을_먼저_남긴다() {
        // 자료 10은 이미 학습 항목과 이어져 있다. 자료 11은 아직 아니다 — 바뀔 것이 더 많다.
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 500);
        List<MaterialSection> all = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            all.add(section(i, 10, "이어진 " + i, false));
        }
        for (int i = 7; i <= 12; i++) {
            all.add(section(i, 11, "새 자료 " + i, false));
        }

        List<MaterialSection> chosen = builder.chooseWithinBudget(all, Set.of(10L));

        assertThat(chosen).isNotEmpty();
        assertThat(chosen).allMatch(s -> s.getMaterialId() == 11L);
        assertThat(chosen.size()).isLessThan(all.size());
    }

    @Test
    void 같은_조건이면_제출_단서가_있는_구간이_먼저다() {
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 300);
        List<MaterialSection> all = List.of(
                section(1, 10, "설명", false),
                section(2, 10, "제출 안내", true),
                section(3, 10, "설명2", false));

        List<MaterialSection> chosen = builder.chooseWithinBudget(all, Set.of());

        assertThat(chosen).extracting(MaterialSection::getSectionId).contains(2L);
    }

    @Test
    void 고른_결과는_원래_순서로_돌려준다() {
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 100_000);
        List<MaterialSection> all = List.of(
                section(1, 10, "이어진", false),   // 이미 이어진 자료 — 우선순위는 뒤
                section(2, 11, "새 자료", false));

        // 우선순위는 2번이 앞이지만, 돌려줄 때는 원래 순서다.
        assertThat(builder.chooseWithinBudget(all, Set.of(10L)))
                .extracting(MaterialSection::getSectionId).containsExactly(1L, 2L);
    }

    @Test
    void 예산이_아무리_작아도_최소_하나는_남긴다() {
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 1);
        List<MaterialSection> all = List.of(section(1, 10, "가", false), section(2, 10, "나", false));

        assertThat(builder.chooseWithinBudget(all, Set.of())).hasSize(1);
    }

    @Test
    void 구간이_없으면_빈_목록이다() {
        ReflectionTestUtils.setField(builder, "sectionCharBudget", 1000);
        assertThat(builder.chooseWithinBudget(List.of(), Set.of())).isEmpty();
    }
}
