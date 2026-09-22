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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 계획 생성이 프로젝트마다 읽는 "자료·진도" 목록(카탈로그). <b>고르지 않는다.</b>
 *
 * <p>2026-09-15까지 여기서 서버가 후보를 골랐다(반드시 포함 + 트리 순서 글자 예산, 그 전에는 과목당 45줄). 역할 순위와
 * 토픽당 구간 2개, 미연결 구간 8개·실행 역할만 같은 규칙 때문에 모델이 보기도 전에 후보가 사라졌다. 지금은:
 * <ul>
 *   <li>후보 = 표식(KNOWN/DEFER)과 이번만 제외를 뺀 학습 항목 전부 + 프로젝트에 연결된 자료의 현재 구간 전부
 *       (토픽 연결 여부·역할과 무관). 이번 요청에서 지정한 자료가 프로젝트에 연결돼 있지 않아도 그 자료의 구간은 후보다.</li>
 *   <li>판단에 반드시 필요한 사실(진행 중·첫 미학습·확정 과제의 마감과 완료·사용자 수정)은 따로 모은다 —
 *       선택 결과와 무관하게 계획 호출에 들어간다. 실행 항목으로 넣으라는 뜻은 아니다.</li>
 *   <li>무엇을 볼지는 {@link com.jungwoo.project.memo.plan.selection.PlanMaterialSelector}의 모델 호출이 고르고,
 *       예산은 호출별 토큰 추정으로 관리한다(묶음 접기·펼치기).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanMaterialContextService {

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

    /**
     * 학습 항목 한 줄의 현재 사실.
     *
     * @param firstUnlearned   표식·제외를 뺀 뒤 트리 순서로 첫 NOT_STARTED 항목
     * @param assignmentDue    연결된 확정·미완료 과제 중 가장 이른 마감
     * @param assignmentLinked 확정·미완료 과제가 연결돼 있다(마감이 없어도 true)
     */
    public record TopicLine(Long topicId, Long parentTopicId, String title, String locator, Long sourceMaterialId,
                            String sourceMaterialFilename, int depth, TopicProgressStatus progress,
                            TopicUserMark mark, LocalDateTime lastStudiedAt, boolean firstUnlearned,
                            LocalDate assignmentDue, boolean assignmentLinked) {

        public TopicLine(Long topicId, Long parentTopicId, String title, String locator, Long sourceMaterialId,
                         String sourceMaterialFilename, int depth, TopicProgressStatus progress,
                         TopicUserMark mark, LocalDateTime lastStudiedAt, boolean firstUnlearned) {
            this(topicId, parentTopicId, title, locator, sourceMaterialId, sourceMaterialFilename, depth, progress,
                    mark, lastStudiedAt, firstUnlearned, null, false);
        }

        /** 판단에 반드시 남아야 하는 항목(선택 결과와 무관). */
        public boolean requiredFact() {
            return progress == TopicProgressStatus.IN_PROGRESS || firstUnlearned || assignmentLinked;
        }

        TopicLine with(boolean first, LocalDate due, boolean assignment) {
            return new TopicLine(topicId, parentTopicId, title, locator, sourceMaterialId, sourceMaterialFilename,
                    depth, progress, mark, lastStudiedAt, first, due, assignment);
        }
    }

    /**
     * 자료 구간 한 줄.
     *
     * @param topicIds            이 구간이 연결된 후보 학습 항목(표식·제외된 항목은 뺀다). 비어 있으면 미연결
     * @param completedAssignment 이 구간에서 나온 과제를 사용자가 완료했다
     * @param openAssignment      이 구간에서 나온 확정·미완료 과제가 있다
     * @param requested           이번 요청에서 사용자가 지정한 자료(또는 구간)다
     */
    public record SectionLine(MaterialSection section, CourseMaterial material, List<String> roles,
                              List<Long> topicIds, boolean completedAssignment, boolean openAssignment,
                              boolean requested) {

        public SectionLine(MaterialSection section, CourseMaterial material, List<String> roles) {
            this(section, material, roles, List.of(), false, false, false);
        }

        public String roleLabels() {
            return roles.stream().map(SectionRole::parseOne).filter(Objects::nonNull)
                    .map(SectionRole::label).distinct().collect(Collectors.joining("/"));
        }
    }

    public record AssignmentLine(CourseAssignment assignment, MaterialSection section, CourseMaterial material) {
    }

    /** 승인 전 구조 제안의 노드 하나. nodeId는 "p{proposalId}:{tempId}" — 학습 항목 id가 아니다. */
    public record ProposedGroup(String nodeId, String title) {
    }

    public record PendingMaterial(Long materialId, String filename, String state, Long courseId) {
    }

    /** 사용자 수정으로 후보에서 빠진 항목. reason: KNOWN / DEFER / THIS_TIME. */
    public record ExcludedTopic(Long topicId, String title, String reason) {
    }

    /**
     * 한 프로젝트의 카탈로그.
     *
     * @param topics       후보 학습 항목(트리 순서)
     * @param sections     후보 구간(자료 → 위치 순)
     * @param excluded     사용자 수정으로 빠진 항목(표식·이번만 제외)
     * @param open         확정·미완료 과제
     * @param completed    완료한 과제(구간 식별 포함 — 개수만 넘기지 않는다)
     * @param unconfirmed  아직 과제인지 확인하지 않은 후보
     * @param materials    이 프로젝트의 자료(요청 지정 자료 포함)
     */
    /** 승인 전 구조 제안의 읽기 전용 색인. 없으면(단위 테스트) 색인 없이 자료별 묶음으로 보인다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jungwoo.project.memo.learning.structure.ProposedTopicIndex proposedTopicIndex;

    public void setProposedTopicIndex(com.jungwoo.project.memo.learning.structure.ProposedTopicIndex index) {
        this.proposedTopicIndex = index;
    }

    /**
     * 학습 항목에 연결되지 않은 구간만 제안 노드로 묶는다. 이미 승인된 구조가 있는 구간은 그 구조가 우선이다.
     * 아무것도 쓰지 않는다 — 승인 전 제안을 읽었다고 학습 항목이나 진도가 생기지 않는다.
     */
    private Map<Long, ProposedGroup> proposedGroups(Long userId, Long courseId, Map<Long, CourseMaterial> materials,
                                                    List<SectionLine> sections) {
        if (proposedTopicIndex == null || courseId == null || sections.stream().noneMatch(s -> s.topicIds().isEmpty())) {
            return Map.of();
        }
        Map<Long, com.jungwoo.project.memo.learning.structure.ProposedTopicIndex.Node> bySection =
                proposedTopicIndex.forCourse(userId, courseId, materials).bySection();
        Map<Long, ProposedGroup> out = new HashMap<>();
        for (SectionLine line : sections) {
            var node = line.topicIds().isEmpty() ? bySection.get(line.section().getSectionId()) : null;
            if (node != null && node.title() != null) {
                out.put(line.section().getSectionId(), new ProposedGroup(node.nodeId(), node.title()));
            }
        }
        return out;
    }

    public record CourseCatalog(Long courseId, String courseTitle, List<TopicLine> topics, List<SectionLine> sections,
                                List<ExcludedTopic> excluded, List<AssignmentLine> open,
                                List<AssignmentLine> completed, List<AssignmentLine> unconfirmed,
                                List<PendingMaterial> pending, List<CourseMaterial> materials, int totalTopics,
                                /**
                                 * 구간 → 승인 전 구조 제안의 노드(읽기 전용 색인). 학습 항목에 연결되지 않은 구간을 목록에서
                                 * 주제로 묶어 보여 주는 데만 쓴다. 학습 항목 id가 아니다.
                                 */
                                Map<Long, ProposedGroup> proposedBySection) {

        public CourseCatalog(Long courseId, String courseTitle, List<TopicLine> topics, List<SectionLine> sections,
                             List<ExcludedTopic> excluded, List<AssignmentLine> open, List<AssignmentLine> completed,
                             List<AssignmentLine> unconfirmed, List<PendingMaterial> pending,
                             List<CourseMaterial> materials, int totalTopics) {
            this(courseId, courseTitle, topics, sections, excluded, open, completed, unconfirmed, pending, materials,
                    totalTopics, Map.of());
        }

        public List<TopicLine> requiredTopics() {
            return topics.stream().filter(TopicLine::requiredFact).toList();
        }

        public List<SectionLine> sectionsOf(Long topicId) {
            return sections.stream().filter(s -> s.topicIds().contains(topicId)).toList();
        }

        public List<SectionLine> unlinkedSections() {
            return sections.stream().filter(s -> s.topicIds().isEmpty()).toList();
        }

        public int candidateCount() {
            return topics.size() + sections.size();
        }
    }

    /**
     * @param courseId              대상 프로젝트
     * @param excludeTopicIds       이번만 제외
     * @param requestedMaterialIds  이번 요청에서 지정한 자료(검증된 것만). 프로젝트에 연결돼 있지 않아도 구간은 후보가 된다
     * @param requestedSectionIds   이번 요청에서 지정한 구간
     */
    @Transactional(readOnly = true)
    public CourseCatalog build(Long userId, Long courseId, String courseTitle, Set<Long> excludeTopicIds,
                               Collection<Long> requestedMaterialIds, Collection<Long> requestedSectionIds) {
        Set<Long> excludedIds = excludeTopicIds == null ? Set.of() : excludeTopicIds;
        Set<Long> requestedMaterials = requestedMaterialIds == null ? Set.of() : new HashSet<>(requestedMaterialIds);
        Set<Long> requestedSections = requestedSectionIds == null ? Set.of() : new HashSet<>(requestedSectionIds);

        List<TopicLine> all = new ArrayList<>();
        for (TopicResponse root : topicService.getTopicTree(userId, courseId)) {
            flatten(all, root, 0);
        }

        List<CourseAssignment> assignmentRows = assignmentService.findByCourses(userId, List.of(courseId));
        List<TopicMaterialLink> courseLinks = topicLinkMapper.findActiveByCourseId(courseId, userId);
        Map<Long, LocalDate> dueByTopic = new HashMap<>();
        Set<Long> openTopicIds = new HashSet<>();
        /*
         * 과제가 가리키는 학습 항목: 과제 행의 topicId, 그리고 과제 구간(sectionId)에 연결된 학습 항목.
         * 자동 분석이 만든 과제는 구간만 갖는 경우가 많다 — topicId만 보면 "첫 미학습"이 사실 과제 제출 항목인데도
         * 과제 표시 없이 실려, 모델이 과제를 대신 수행하는 항목을 만들었다(2026-09-15 실호출).
         */
        Map<Long, List<Long>> linkedTopicsBySection = new HashMap<>();
        for (TopicMaterialLink link : courseLinks) {
            if (link.getSectionId() != null && link.getSectionId() != TopicMaterialLink.WHOLE_MATERIAL) {
                linkedTopicsBySection.computeIfAbsent(link.getSectionId(), k -> new ArrayList<>()).add(link.getTopicId());
            }
        }
        for (CourseAssignment a : assignmentRows) {
            if (a.getConfirmStatus() != AssignmentConfirmStatus.CONFIRMED || a.isCompleted()) {
                continue;
            }
            Set<Long> topics = new HashSet<>();
            if (a.getTopicId() != null) {
                topics.add(a.getTopicId());
            }
            if (a.getSectionId() != null) {
                topics.addAll(linkedTopicsBySection.getOrDefault(a.getSectionId(), List.of()));
            }
            LocalDate due = a.dueDay();
            for (Long topicId : topics) {
                openTopicIds.add(topicId);
                if (due != null) {
                    dueByTopic.merge(topicId, due, (x, y) -> x.isBefore(y) ? x : y);
                }
            }
        }

        List<TopicLine> candidates = new ArrayList<>();
        List<ExcludedTopic> excluded = new ArrayList<>();
        boolean firstMarked = false;
        for (TopicLine line : all) {
            if (line.mark() == TopicUserMark.KNOWN || line.mark() == TopicUserMark.DEFER) {
                excluded.add(new ExcludedTopic(line.topicId(), line.title(), line.mark().name()));
                continue;
            }
            if (excludedIds.contains(line.topicId())) {
                excluded.add(new ExcludedTopic(line.topicId(), line.title(), "THIS_TIME"));
                continue;
            }
            boolean first = !firstMarked && line.progress() == TopicProgressStatus.NOT_STARTED;
            if (first) {
                firstMarked = true;
            }
            candidates.add(line.with(first, dueByTopic.get(line.topicId()), openTopicIds.contains(line.topicId())));
        }
        Set<Long> candidateTopicIds = candidates.stream().map(TopicLine::topicId).collect(Collectors.toSet());
        Set<Long> excludedTopicIds = excluded.stream().map(ExcludedTopic::topicId).collect(Collectors.toSet());

        // 자료: 프로젝트에 연결된 것 + 이번 요청에서 지정한 것(연결 여부와 무관, 호출자가 소유·범위를 검증했다).
        Map<Long, CourseMaterial> materials = new LinkedHashMap<>();
        for (CourseMaterial m : courseMaterialMapper.findByCourseIdAndUserId(courseId, userId)) {
            materials.put(m.getMaterialId(), m);
        }
        for (Long requested : requestedMaterials) {
            if (!materials.containsKey(requested)) {
                CourseMaterial m = courseMaterialMapper.findByIdAndUserId(requested, userId);
                if (m != null) {
                    materials.put(m.getMaterialId(), m);
                }
            }
        }

        Map<Long, List<Long>> topicsBySection = new HashMap<>();
        Map<Long, Boolean> linkedOnlyToExcluded = new HashMap<>();
        for (TopicMaterialLink link : courseLinks) {
            if (link.getSectionId() == null || link.getSectionId() == TopicMaterialLink.WHOLE_MATERIAL) {
                continue;
            }
            if (candidateTopicIds.contains(link.getTopicId())) {
                topicsBySection.computeIfAbsent(link.getSectionId(), k -> new ArrayList<>()).add(link.getTopicId());
                linkedOnlyToExcluded.put(link.getSectionId(), false);
            } else if (excludedTopicIds.contains(link.getTopicId())) {
                linkedOnlyToExcluded.putIfAbsent(link.getSectionId(), true);
            }
        }

        Map<Long, List<CourseAssignment>> assignmentsBySection = new HashMap<>();
        for (CourseAssignment a : assignmentRows) {
            if (a.getSectionId() != null) {
                assignmentsBySection.computeIfAbsent(a.getSectionId(), k -> new ArrayList<>()).add(a);
            }
        }

        List<SectionLine> sections = new ArrayList<>();
        Map<Long, MaterialSection> sectionById = new HashMap<>();
        if (!materials.isEmpty()) {
            for (MaterialSection section : sectionMapper.findActiveByMaterialIds(new ArrayList<>(materials.keySet()), userId)) {
                CourseMaterial material = materials.get(section.getMaterialId());
                if (material == null || section.getExcerpt() == null
                        || !Objects.equals(material.getFileHash(), section.getFileHash())) {
                    continue; // 삭제된 자료(발췌가 비어 있다)나 옛 해시 구간은 후보가 아니다.
                }
                sectionById.put(section.getSectionId(), section);
                boolean requested = requestedMaterials.contains(material.getMaterialId())
                        || requestedSections.contains(section.getSectionId());
                if (Boolean.TRUE.equals(linkedOnlyToExcluded.get(section.getSectionId())) && !requested) {
                    continue; // 사용자가 안다고/이번만 뺀 항목에만 연결된 구간. 요청 지정 자료면 남긴다.
                }
                List<CourseAssignment> mine = assignmentsBySection.getOrDefault(section.getSectionId(), List.of());
                boolean completed = mine.stream().anyMatch(a -> a.getConfirmStatus() == AssignmentConfirmStatus.CONFIRMED
                        && a.isCompleted());
                boolean open = mine.stream().anyMatch(a -> a.getConfirmStatus() == AssignmentConfirmStatus.CONFIRMED
                        && !a.isCompleted());
                sections.add(new SectionLine(section, material, roles(section),
                        List.copyOf(topicsBySection.getOrDefault(section.getSectionId(), List.of())),
                        completed, open, requested));
            }
        }

        List<AssignmentLine> open = new ArrayList<>();
        List<AssignmentLine> completed = new ArrayList<>();
        List<AssignmentLine> unconfirmed = new ArrayList<>();
        for (CourseAssignment a : assignmentRows) {
            AssignmentConfirmStatus status = a.getConfirmStatus();
            if (status == AssignmentConfirmStatus.NOT_ASSIGNMENT || status == AssignmentConfirmStatus.DUPLICATE) {
                continue;
            }
            MaterialSection section = a.getSectionId() == null ? null : sectionById.get(a.getSectionId());
            if (section == null && a.getSectionId() != null) {
                section = sectionMapper.findByIdAndUserId(a.getSectionId(), userId);
            }
            AssignmentLine line = new AssignmentLine(a, section,
                    a.getMaterialId() == null ? null : materials.get(a.getMaterialId()));
            if (status != AssignmentConfirmStatus.CONFIRMED) {
                unconfirmed.add(line);
            } else if (a.isCompleted()) {
                completed.add(line);
            } else {
                open.add(line);
            }
        }

        List<CourseMaterial> materialList = new ArrayList<>(materials.values());
        return new CourseCatalog(courseId, courseTitle, candidates, sections, excluded, open, completed, unconfirmed,
                pendingOf(userId, courseId, materialList), materialList, all.size(),
                proposedGroups(userId, courseId, materials, sections));
    }

    /**
     * 대상 프로젝트 어디에도 연결되지 않은 지정 자료의 카탈로그(학습 항목 없음). 호출자가 소유·범위를 검증했다 —
     * 이번 요청에서 사용자가 이 자료를 직접 지정한 경우만 여기로 온다.
     */
    @Transactional(readOnly = true)
    public CourseCatalog buildUnscoped(Long userId, Collection<Long> materialIds, Collection<Long> requestedMaterialIds,
                                       Collection<Long> requestedSectionIds) {
        Set<Long> requestedMaterials = requestedMaterialIds == null ? Set.of() : new HashSet<>(requestedMaterialIds);
        Set<Long> requestedSections = requestedSectionIds == null ? Set.of() : new HashSet<>(requestedSectionIds);
        Map<Long, CourseMaterial> materials = new LinkedHashMap<>();
        for (Long id : materialIds) {
            CourseMaterial m = courseMaterialMapper.findByIdAndUserId(id, userId);
            if (m != null) {
                materials.put(m.getMaterialId(), m);
            }
        }
        List<SectionLine> sections = new ArrayList<>();
        if (!materials.isEmpty()) {
            for (MaterialSection section : sectionMapper.findActiveByMaterialIds(new ArrayList<>(materials.keySet()), userId)) {
                CourseMaterial material = materials.get(section.getMaterialId());
                if (material == null || section.getExcerpt() == null
                        || !Objects.equals(material.getFileHash(), section.getFileHash())) {
                    continue;
                }
                boolean requested = requestedMaterials.contains(material.getMaterialId())
                        || requestedSections.contains(section.getSectionId());
                sections.add(new SectionLine(section, material, roles(section), List.of(), false, false, requested));
            }
        }
        List<CourseMaterial> list = new ArrayList<>(materials.values());
        return new CourseCatalog(null, null, List.of(), sections, List.of(), List.of(), List.of(), List.of(),
                pendingOf(userId, null, list), list, 0);
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

    public List<String> rolesOf(MaterialSection section) {
        return roles(section);
    }

    List<String> roles(MaterialSection section) {
        try {
            return section.getRolesJson() == null ? List.of() : objectMapper.readValue(section.getRolesJson(), STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 프롬프트 한 줄용 짧은 설명. 원문 발췌가 아니라 목록 설명이다 — 원문은 선택 뒤 조회한다. */
    public static String shortExcerpt(String excerpt) {
        if (excerpt == null) {
            return null;
        }
        String flat = excerpt.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
    }
}
