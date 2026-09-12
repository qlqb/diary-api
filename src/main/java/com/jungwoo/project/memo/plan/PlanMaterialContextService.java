package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.SectionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 계획 생성이 프로젝트마다 읽는 "자료·진도" 묶음. 기본 AI 경로와 판단 경로가 같은 것을 본다.
 *
 * <p>후보 선택 규칙(예산이 있어도 중요한 후보가 업로드 순서·자료 형식 때문에 빠지지 않게):
 * <ol>
 *   <li>진행 중(IN_PROGRESS) 항목</li>
 *   <li>확정·미완료 과제와 연결된 항목</li>
 *   <li>첫 미학습 항목부터 순서대로(주차와 무관 — 학기 중반에 기록이 없어도 1주차가 후보에 남는다)</li>
 *   <li>나머지 순서대로. 학습 완료(LEARNED)는 표시만 붙여 뒤로</li>
 * </ol>
 * 「이미 알아요」/「나중에」 표식이 있는 항목과 이번 요청에서 제외한 항목은 후보에서 빠지고 개수만 남는다.
 * 포함된 자식의 조상은 함께 포함해 계층이 끊기지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanMaterialContextService {

    /** 프로젝트당 학습 항목 줄 상한. 실데이터(과목당 최대 46개)를 대부분 자르지 않는 값이다. */
    public static final int MAX_TOPIC_LINES = 45;
    /** 항목 하나에 붙이는 자료 구간 상한과 프로젝트당 총 상한. */
    static final int MAX_SECTIONS_PER_TOPIC = 2;
    static final int MAX_SECTION_LINES = 30;
    static final int MAX_UNLINKED_SECTIONS = 8;
    static final int EXCERPT_CHARS = 90;

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final TopicService topicService;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final MaterialSectionMapper sectionMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final CourseAssignmentService assignmentService;
    private final MaterialAnalysisJobService analysisJobService;
    private final ObjectMapper objectMapper;

    public record TopicLine(Long topicId, Long parentTopicId, String title, String locator, Long sourceMaterialId,
                            String sourceMaterialFilename, int depth, TopicProgressStatus progress,
                            TopicUserMark mark, LocalDateTime lastStudiedAt, boolean firstUnlearned) {
    }

    public record SectionLine(MaterialSection section, CourseMaterial material, List<String> roles) {
        public String roleLabels() {
            return roles.stream().map(SectionRole::parseOne).filter(Objects::nonNull)
                    .map(SectionRole::label).distinct().collect(Collectors.joining("/"));
        }
    }

    public record AssignmentLine(CourseAssignment assignment, MaterialSection section, CourseMaterial material) {
    }

    public record PendingMaterial(Long materialId, String filename, String state, Long courseId) {
    }

    public record CourseBundle(List<TopicLine> topics, int excludedByMark, int excludedThisTime, int totalTopics,
                               Map<Long, List<SectionLine>> sectionsByTopic, List<SectionLine> unlinkedSections,
                               List<AssignmentLine> assignments, int completedAssignments,
                               List<PendingMaterial> pendingMaterials) {
    }

    @Transactional(readOnly = true)
    public CourseBundle build(Long userId, Long courseId, Set<Long> excludeTopicIds) {
        List<TopicLine> all = new ArrayList<>();
        for (TopicResponse root : topicService.getTopicTree(userId, courseId)) {
            flatten(all, root, 0);
        }
        List<CourseAssignment> assignmentRows = assignmentService.findByCourses(userId, List.of(courseId));
        Set<Long> assignmentTopicIds = assignmentRows.stream()
                .filter(a -> a.getConfirmStatus() == AssignmentConfirmStatus.CONFIRMED && !a.isCompleted())
                .map(CourseAssignment::getTopicId).filter(Objects::nonNull).collect(Collectors.toSet());

        Selection selection = select(all, excludeTopicIds == null ? Set.of() : excludeTopicIds, assignmentTopicIds,
                MAX_TOPIC_LINES);

        Map<Long, List<SectionLine>> sectionsByTopic = sectionsFor(userId, selection.lines());
        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId);
        Map<Long, CourseMaterial> materialsById = materials.stream()
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
        List<SectionLine> unlinked = unlinkedSections(userId, courseId, materials, sectionsByTopic);

        List<AssignmentLine> assignments = new ArrayList<>();
        int completed = 0;
        Map<Long, MaterialSection> sectionCache = new HashMap<>();
        for (CourseAssignment a : assignmentRows) {
            if (a.getConfirmStatus() == AssignmentConfirmStatus.NOT_ASSIGNMENT
                    || a.getConfirmStatus() == AssignmentConfirmStatus.DUPLICATE) {
                continue;
            }
            if (a.isCompleted()) {
                completed++;
                continue;
            }
            MaterialSection section = a.getSectionId() == null ? null
                    : sectionCache.computeIfAbsent(a.getSectionId(), id -> sectionMapper.findByIdAndUserId(id, userId));
            assignments.add(new AssignmentLine(a, section,
                    a.getMaterialId() == null ? null : materialsById.get(a.getMaterialId())));
        }

        return new CourseBundle(selection.lines(), selection.excludedByMark(), selection.excludedThisTime(), all.size(),
                sectionsByTopic, unlinked, assignments, completed, pendingOf(userId, courseId, materials));
    }

    /** 대상 프로젝트 전체의 미반영 자료. 응답의 pendingMaterials. */
    @Transactional(readOnly = true)
    public List<PendingMaterial> pendingMaterials(Long userId, List<Long> courseIds) {
        List<PendingMaterial> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long courseId : courseIds == null ? List.<Long>of() : courseIds) {
            for (PendingMaterial pending : pendingOf(userId, courseId,
                    courseMaterialMapper.findByCourseIdAndUserId(courseId, userId))) {
                if (seen.add(pending.materialId())) {
                    out.add(pending);
                }
            }
        }
        return out;
    }

    // ===== 선택 =====

    record Selection(List<TopicLine> lines, int excludedByMark, int excludedThisTime) {
    }

    static Selection select(List<TopicLine> all, Set<Long> excludeTopicIds, Set<Long> assignmentTopicIds, int cap) {
        int excludedByMark = 0;
        int excludedThisTime = 0;
        List<TopicLine> eligible = new ArrayList<>();
        boolean firstMarked = false;
        for (TopicLine line : all) {
            if (line.mark() == TopicUserMark.KNOWN || line.mark() == TopicUserMark.DEFER) {
                excludedByMark++;
                continue;
            }
            if (excludeTopicIds.contains(line.topicId())) {
                excludedThisTime++;
                continue;
            }
            boolean first = !firstMarked && line.progress() == TopicProgressStatus.NOT_STARTED;
            if (first) {
                firstMarked = true;
            }
            eligible.add(first ? new TopicLine(line.topicId(), line.parentTopicId(), line.title(), line.locator(),
                    line.sourceMaterialId(), line.sourceMaterialFilename(), line.depth(), line.progress(), line.mark(),
                    line.lastStudiedAt(), true) : line);
        }
        if (eligible.size() <= cap) {
            return new Selection(eligible, excludedByMark, excludedThisTime);
        }
        Map<Long, TopicLine> byId = new LinkedHashMap<>();
        for (TopicLine line : eligible) {
            byId.put(line.topicId(), line);
        }
        Set<Long> chosen = new LinkedHashSet<>();
        // 1. 진행 중  2. 과제 연결  3. 첫 미학습부터 순서대로(미학습만)  4. 나머지
        for (TopicLine line : eligible) {
            if (line.progress() == TopicProgressStatus.IN_PROGRESS) {
                addWithAncestors(chosen, line, byId, cap);
            }
        }
        for (TopicLine line : eligible) {
            if (assignmentTopicIds.contains(line.topicId())) {
                addWithAncestors(chosen, line, byId, cap);
            }
        }
        for (TopicLine line : eligible) {
            if (line.progress() == TopicProgressStatus.NOT_STARTED) {
                addWithAncestors(chosen, line, byId, cap);
            }
        }
        for (TopicLine line : eligible) {
            addWithAncestors(chosen, line, byId, cap);
        }
        List<TopicLine> ordered = eligible.stream().filter(l -> chosen.contains(l.topicId())).toList();
        return new Selection(ordered, excludedByMark, excludedThisTime);
    }

    private static void addWithAncestors(Set<Long> chosen, TopicLine line, Map<Long, TopicLine> byId, int cap) {
        if (chosen.contains(line.topicId())) {
            return;
        }
        List<Long> chain = new ArrayList<>();
        TopicLine cursor = line;
        int guard = 0;
        while (cursor != null && guard++ < 100) {
            if (!chosen.contains(cursor.topicId())) {
                chain.add(0, cursor.topicId());
            }
            cursor = cursor.parentTopicId() == null ? null : byId.get(cursor.parentTopicId());
        }
        if (chosen.size() + chain.size() > cap) {
            return;
        }
        chosen.addAll(chain);
    }

    private void flatten(List<TopicLine> out, TopicResponse node, int depth) {
        out.add(new TopicLine(node.getTopicId(), node.getParentTopicId(), node.getTitle(), node.getSourceLocator(),
                node.getSourceMaterialId(), node.getSourceMaterialFilename(), depth, node.getProgressStatus(),
                node.getUserMark(), node.getLastStudiedAt(), false));
        if (node.getChildren() != null) {
            for (TopicResponse child : node.getChildren()) {
                flatten(out, child, depth + 1);
            }
        }
    }

    // ===== 자료 구간 =====

    private Map<Long, List<SectionLine>> sectionsFor(Long userId, List<TopicLine> lines) {
        Map<Long, List<SectionLine>> out = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return out;
        }
        List<Long> topicIds = lines.stream().map(TopicLine::topicId).toList();
        List<TopicMaterialLink> links = topicLinkMapper.findActiveByTopicIds(topicIds, userId);
        List<Long> sectionIds = links.stream().map(TopicMaterialLink::getSectionId)
                .filter(id -> id != null && id != TopicMaterialLink.WHOLE_MATERIAL).distinct().toList();
        if (sectionIds.isEmpty()) {
            return out;
        }
        Map<Long, MaterialSection> sections = sectionMapper.findByIdsAndUserId(sectionIds, userId).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .collect(Collectors.toMap(MaterialSection::getSectionId, s -> s, (a, b) -> a));
        Map<Long, CourseMaterial> materials = materialsOf(userId,
                sections.values().stream().map(MaterialSection::getMaterialId).distinct().toList());
        int total = 0;
        for (TopicLine line : lines) {
            List<SectionLine> mine = new ArrayList<>();
            for (TopicMaterialLink link : links) {
                if (!link.getTopicId().equals(line.topicId())) {
                    continue;
                }
                MaterialSection section = sections.get(link.getSectionId());
                CourseMaterial material = section == null ? null : materials.get(section.getMaterialId());
                if (section == null || material == null || section.getExcerpt() == null) {
                    continue; // 삭제된 자료의 구간은 발췌가 비어 있다 — 읽은 척하지 않는다.
                }
                mine.add(new SectionLine(section, material, roles(section)));
            }
            // 문제·예제를 앞에. 서버가 "무엇을 먼저 할지"를 정하는 것이 아니라, 잘릴 때 남길 것을 정한다.
            mine.sort(Comparator.comparingInt(s -> rolePriority(s.roles())));
            List<SectionLine> kept = mine.subList(0, Math.min(mine.size(), MAX_SECTIONS_PER_TOPIC));
            if (!kept.isEmpty() && total < MAX_SECTION_LINES) {
                List<SectionLine> limited = new ArrayList<>(kept.subList(0, Math.min(kept.size(), MAX_SECTION_LINES - total)));
                out.put(line.topicId(), limited);
                total += limited.size();
            }
        }
        return out;
    }

    private List<SectionLine> unlinkedSections(Long userId, Long courseId, List<CourseMaterial> materials,
                                               Map<Long, List<SectionLine>> linked) {
        if (materials.isEmpty()) {
            return List.of();
        }
        Set<Long> linkedSectionIds = new HashSet<>();
        for (TopicMaterialLink link : topicLinkMapper.findActiveByCourseId(courseId, userId)) {
            linkedSectionIds.add(link.getSectionId());
        }
        Map<Long, CourseMaterial> byId = materials.stream()
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
        List<SectionLine> out = new ArrayList<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(new ArrayList<>(byId.keySet()), userId)) {
            CourseMaterial material = byId.get(section.getMaterialId());
            if (material == null || section.getExcerpt() == null || linkedSectionIds.contains(section.getSectionId())
                    || !Objects.equals(material.getFileHash(), section.getFileHash())) {
                continue;
            }
            List<String> roles = roles(section);
            boolean actionable = roles.contains("EXERCISE") || roles.contains("EXAMPLE") || roles.contains("ASSIGNMENT");
            if (!actionable) {
                continue;
            }
            out.add(new SectionLine(section, material, roles));
        }
        out.sort(Comparator.comparingInt(s -> rolePriority(s.roles())));
        return out.subList(0, Math.min(out.size(), MAX_UNLINKED_SECTIONS));
    }

    private List<PendingMaterial> pendingOf(Long userId, Long courseId, List<CourseMaterial> materials) {
        if (materials.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialAnalysisJob> latestContent = new HashMap<>();
        for (MaterialAnalysisJob job : analysisJobService.findByMaterials(userId,
                materials.stream().map(CourseMaterial::getMaterialId).toList())) {
            if (job.getJobKind() != AnalysisJobKind.CONTENT) {
                continue;
            }
            latestContent.merge(job.getMaterialId(), job,
                    (a, b) -> a.getJobId() > b.getJobId() ? a : b);
        }
        List<PendingMaterial> out = new ArrayList<>();
        for (CourseMaterial material : materials) {
            if (material.getExtractionStatus() != ExtractionStatus.SUCCESS) {
                continue; // 추출 실패는 "분석 중"이 아니다. 자료 화면이 따로 말한다.
            }
            MaterialAnalysisJob job = latestContent.get(material.getMaterialId());
            boolean usable = job != null && job.getStatus() != null && job.getStatus().hasUsableResult()
                    && Objects.equals(job.getFileHash(), material.getFileHash());
            if (!usable) {
                out.add(new PendingMaterial(material.getMaterialId(), material.getOriginalFilename(),
                        job == null ? "QUEUED" : job.getStatus().name(), courseId));
            }
        }
        return out;
    }

    private Map<Long, CourseMaterial> materialsOf(Long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(ids, userId).stream()
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
    }

    private List<String> roles(MaterialSection section) {
        try {
            return section.getRolesJson() == null ? List.of() : objectMapper.readValue(section.getRolesJson(), STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int rolePriority(List<String> roles) {
        if (roles.contains("ASSIGNMENT")) {
            return 0;
        }
        if (roles.contains("EXERCISE")) {
            return 1;
        }
        if (roles.contains("EXAMPLE")) {
            return 2;
        }
        if (roles.contains("CONCEPT")) {
            return 3;
        }
        return 4;
    }

    /** 프롬프트 한 줄용 짧은 발췌. */
    public static String shortExcerpt(String excerpt) {
        if (excerpt == null) {
            return null;
        }
        String flat = excerpt.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
    }
}
