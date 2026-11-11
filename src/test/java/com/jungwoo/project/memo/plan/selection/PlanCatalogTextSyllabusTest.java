package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.SectionLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 강의계획서 구간은 후보 줄에서 "학습 본문 아님"으로 보여야 선택 모델이 강의자료 대신 고르지 않는다. */
class PlanCatalogTextSyllabusTest {

    @Test
    void 강의계획서_구간에만_표시가_붙는다() {
        MaterialSection section = new MaterialSection();
        section.setSectionId(437L);
        section.setMaterialId(1L);
        section.setDisplayTitle("리스트·스택·큐·트리와 중간고사");
        CourseMaterial material = CourseMaterial.builder().materialId(1L).originalFilename("자료구조.pdf").build();

        SectionLine syllabus = new SectionLine(section, material, List.of(), List.of(), false, false, false,
                null, false, null, true);
        SectionLine slide = new SectionLine(section, material, List.of(), List.of(), false, false, false);

        assertThat(PlanCatalogText.sectionText(syllabus, null, true)).contains(PlanCatalogText.SYLLABUS_MARK);
        assertThat(PlanCatalogText.sectionText(slide, null, true)).doesNotContain("강의계획서");
    }
}
