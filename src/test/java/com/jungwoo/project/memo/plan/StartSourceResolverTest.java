package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.plan.dto.StartSource;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 시작 자료는 인용 순서를 따르되, 강의계획서 구간은 다른 근거가 없을 때만 보인다. */
class StartSourceResolverTest {

    private static final long USER = 2L;
    private static final long SYLLABUS = 100L;
    private static final long SLIDE = 200L;

    private final MaterialSectionMapper sectionMapper = mock(MaterialSectionMapper.class);
    private final CourseMaterialMapper materialMapper = mock(CourseMaterialMapper.class);
    private final TopicMaterialLinkMapper topicLinkMapper = mock(TopicMaterialLinkMapper.class);
    private final MaterialLinkMapper materialLinkMapper = mock(MaterialLinkMapper.class);
    private final StartSourceResolver resolver =
            new StartSourceResolver(sectionMapper, materialMapper, topicLinkMapper, materialLinkMapper);

    private final List<MaterialSection> sections = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sections.add(section(437L, SYLLABUS));   // 강의계획서 p.3
        sections.add(section(438L, SYLLABUS));   // 강의계획서 p.4
        sections.add(section(2255L, SLIDE));     // ch05 스택 슬라이드
        when(sectionMapper.findByIdsAndUserId(anyList(), eq(USER))).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            // 일부러 역순으로 돌려준다 — 인용 순서는 resolver가 지켜야 한다.
            return sections.stream().filter(s -> ids.contains(s.getSectionId())).toList().reversed();
        });
        when(materialMapper.findByIdsAndUserIdIncludingDeleted(anyList(), eq(USER))).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> CourseMaterial.builder().materialId(id).status(MaterialStatus.ACTIVE)
                    .fileHash("h").originalFilename(id == SYLLABUS ? "자료구조.pdf" : "ch05_스택(Stack).pptx").build())
                    .toList();
        });
        when(materialLinkMapper.findByMaterialIdAndUserId(anyLong(), eq(USER))).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return List.of(MaterialLink.builder().materialId(id).courseId(934L)
                    .materialType(id == SYLLABUS ? MaterialType.SYLLABUS : MaterialType.PROFESSOR_SLIDE).build());
        });
        when(topicLinkMapper.findActiveByTopicIds(anyList(), eq(USER))).thenReturn(List.of());
    }

    @Test
    void 강의계획서를_먼저_인용해도_뒤에_인용한_강의자료가_시작_자료다() {
        StartSource start = resolver.resolve(USER, evidence("s57", "s58", "s70"), provenance());

        assertThat(start.sectionId()).isEqualTo(2255L);
    }

    @Test
    void 강의계획서만_인용했고_인용한_학습_항목에_강의자료가_연결돼_있으면_그_구간이다() {
        TopicMaterialLink link = new TopicMaterialLink();
        link.setTopicId(1434L);
        link.setSectionId(2255L);
        when(topicLinkMapper.findActiveByTopicIds(anyList(), eq(USER))).thenReturn(List.of(link));

        StartSource start = resolver.resolve(USER, evidence("s57", "t1"), provenance());

        assertThat(start.sectionId()).isEqualTo(2255L);
    }

    @Test
    void 강의계획서밖에_없으면_인용한_첫_강의계획서_구간을_그대로_보인다() {
        StartSource start = resolver.resolve(USER, evidence("s58", "s57"), provenance());

        assertThat(start.sectionId()).isEqualTo(438L);
    }

    private static MaterialSection section(long id, long materialId) {
        MaterialSection s = new MaterialSection();
        s.setSectionId(id);
        s.setMaterialId(materialId);
        s.setStatus("ACTIVE");
        s.setFileHash("h");
        return s;
    }

    private static PlanItemEvidence evidence(String... refs) {
        List<String> ids = List.of(refs);
        PlanItemEvidence e = mock(PlanItemEvidence.class);
        when(e.refIds()).thenReturn(ids);
        return e;
    }

    private static PlanProvenance provenance() {
        List<ProvidedSource> sources = List.of(
                source("s57", ProvenanceSourceType.MATERIAL_SECTION, 437L),
                source("s58", ProvenanceSourceType.MATERIAL_SECTION, 438L),
                source("s70", ProvenanceSourceType.MATERIAL_SECTION, 2255L),
                source("t1", ProvenanceSourceType.TOPIC, 1434L));
        PlanProvenance p = mock(PlanProvenance.class);
        when(p.providedSources()).thenReturn(sources);
        return p;
    }

    private static ProvidedSource source(String ref, ProvenanceSourceType type, long id) {
        ProvidedSource s = mock(ProvidedSource.class);
        when(s.refId()).thenReturn(ref);
        when(s.sourceType()).thenReturn(type);
        when(s.sourceId()).thenReturn(id);
        return s;
    }
}
