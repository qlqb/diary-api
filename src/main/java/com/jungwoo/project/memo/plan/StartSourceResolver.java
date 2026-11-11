package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import com.jungwoo.project.memo.plan.dto.StartSource;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 항목을 "어디서 시작하나" — 그 항목이 실제로 인용한 자료 구간 중 첫 번째의 파일·위치.
 *
 * <p>모델이 새로 쓴 값이 아니다. 인용 번호(refIds)를 그 회차 스냅샷의 원본 행으로 되짚고, 지금의 구간·자료 행과
 * 대조한다. 그래서 초안 화면·오늘·계획·실행 화면이 같은 값을 본다(모델 호출 없음).
 *
 * <ul>
 *   <li>인용 순서를 지킨다 — 모델이 먼저 든 근거가 시작 자리다.</li>
 *   <li>구간 인용이 없고 학습 항목만 인용했으면 그 항목에 연결된 첫 구간으로 보강한다. 그것도 실제 연결이다.</li>
 *   <li>강의계획서(SYLLABUS로 연결된 자료)의 구간은 일정·범위의 근거라 시작 자리로 맨 뒤에 둔다. 인용한 다른 구간,
 *       인용한 학습 항목의 연결 구간이 없을 때만 강의계획서 구간을 보인다(2026-10-07: 스택 항목의 시작 자료가
 *       강의계획서 p.3으로 보였다).</li>
 *   <li>파일이 바뀌었거나(해시) 구간이 내려갔으면 CHANGED, 지워졌으면 DELETED. 그때 쪽 번호는 주지 않는다 —
 *       옛 파일의 쪽으로 새 파일을 열게 하지 않는다.</li>
 *   <li>쪽 이동은 PDF 페이지 단위일 때만 준다. 슬라이드·셀·구간은 위치를 글로만 알린다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StartSourceResolver {

    private final MaterialSectionMapper sectionMapper;
    private final CourseMaterialMapper materialMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final MaterialLinkMapper materialLinkMapper;

    public StartSource resolve(Long userId, PlanItemEvidence evidence, PlanProvenance provenance) {
        MaterialSection section = firstCitedSection(userId, evidence, provenance);
        return section == null ? null : of(userId, section);
    }

    /** 이미 고른 구간으로 만든다. */
    public StartSource of(Long userId, MaterialSection section) {
        List<CourseMaterial> materials = materialMapper.findByIdsAndUserIdIncludingDeleted(
                List.of(section.getMaterialId()), userId);
        CourseMaterial material = materials.isEmpty() ? null : materials.get(0);
        String state;
        if (material == null || material.getStatus() != MaterialStatus.ACTIVE) {
            state = "DELETED";
        } else if (!"ACTIVE".equals(section.getStatus())
                || !Objects.equals(material.getFileHash(), section.getFileHash())) {
            state = "CHANGED";
        } else {
            state = "AVAILABLE";
        }
        Integer page = "AVAILABLE".equals(state) && section.getUnitType() == TextUnitType.PDF_PAGE
                ? section.getUnitStart() : null;
        return new StartSource(section.getMaterialId(), section.getSectionId(),
                material == null ? null : material.getOriginalFilename(),
                material == null ? null : material.getContentType(),
                section.getDisplayTitle(), section.locator(), section.getTaskText(), page, state);
    }

    private MaterialSection firstCitedSection(Long userId, PlanItemEvidence evidence, PlanProvenance provenance) {
        if (evidence == null || provenance == null || evidence.refIds() == null || provenance.providedSources() == null) {
            return null;
        }
        List<Long> sectionIds = new ArrayList<>();
        List<Long> topicIds = new ArrayList<>();
        for (String ref : evidence.refIds()) {
            for (ProvidedSource source : provenance.providedSources()) {
                if (!Objects.equals(ref, source.refId()) || source.sourceId() == null) {
                    continue;
                }
                if (source.sourceType() == ProvenanceSourceType.MATERIAL_SECTION && !sectionIds.contains(source.sourceId())) {
                    sectionIds.add(source.sourceId());
                }
                if (source.sourceType() == ProvenanceSourceType.TOPIC) {
                    topicIds.add(source.sourceId());
                }
            }
        }
        Map<Long, Boolean> syllabusByMaterial = new HashMap<>();
        MaterialSection syllabusFallback = null;
        for (MaterialSection row : ordered(userId, sectionIds)) {
            if (!isSyllabus(userId, row.getMaterialId(), syllabusByMaterial)) {
                return row;
            }
            if (syllabusFallback == null) {
                syllabusFallback = row;
            }
        }
        if (!topicIds.isEmpty()) {
            List<Long> linked = new ArrayList<>();
            for (TopicMaterialLink link : topicLinkMapper.findActiveByTopicIds(topicIds, userId)) {
                if (link.getSectionId() != null && link.getSectionId() != TopicMaterialLink.WHOLE_MATERIAL
                        && !linked.contains(link.getSectionId())) {
                    linked.add(link.getSectionId());
                }
            }
            for (MaterialSection row : ordered(userId, linked)) {
                if (!isSyllabus(userId, row.getMaterialId(), syllabusByMaterial)) {
                    return row;
                }
                if (syllabusFallback == null) {
                    syllabusFallback = row;
                }
            }
        }
        return syllabusFallback;
    }

    /** 구간 행을 주어진 id 순서대로. 없는 id는 건너뛴다. */
    private List<MaterialSection> ordered(Long userId, List<Long> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialSection> byId = new HashMap<>();
        for (MaterialSection row : sectionMapper.findByIdsAndUserId(sectionIds, userId)) {
            byId.put(row.getSectionId(), row);
        }
        return sectionIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** 어느 프로젝트에서든 강의계획서로 연결된 자료인가. */
    private boolean isSyllabus(Long userId, Long materialId, Map<Long, Boolean> cache) {
        if (materialId == null) {
            return false;
        }
        return cache.computeIfAbsent(materialId, id -> materialLinkMapper.findByMaterialIdAndUserId(id, userId).stream()
                .anyMatch(l -> l.getMaterialType() == MaterialType.SYLLABUS));
    }
}
