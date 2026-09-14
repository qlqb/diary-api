package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * 계획 생성이 프로젝트마다 읽는 "자료·진도" 묶음. 기본 AI 경로와 판단 경로가 같은 것을 본다.
 *
 * <p>후보 선택은 두 단계다(고정 개수 컷도, 우선순위 재정렬도 없다):
 * <ol>
 *   <li><b>반드시 포함</b>: 진행 중(IN_PROGRESS) 항목, 첫 미학습 항목, 확정·미완료 과제와 연결된 항목,
 *       사용자가 직접 자료를 연결한 항목. 예산과 무관하게 들어가고, 조상도 함께 들어간다.
 *       판단 입력에 반드시 있어야 할 사실이지, 실행 항목으로 만들라는 뜻은 아니다 — 그 구분은 프롬프트가 말한다.</li>
 *   <li><b>예산 채우기</b>: 나머지를 트리 순서대로, 첫 미학습 이후(아직 배우지 않은 쪽)부터 채우고 예산이 남으면
 *       그 앞(학습 완료 등)도 채운다. 예산은 실제 토큰 측정에서 정한 글자 수({@code plan.draft.context-budget-chars-per-course})다.
 *       실데이터(과목당 최대 77개 항목)는 기본값 안에 다 들어간다 — 예산은 폭주 방지선이지 잘라내는 규칙이 아니다.</li>
 * </ol>
 * 「이미 알아요」/「나중에」 표식이 있는 항목과 이번 요청에서 제외한 항목은 후보에서 빠지고 개수만 남는다.
 * 출력 순서는 항상 트리 순서다 — 선택은 "무엇을 싣나"만 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanMaterialContextService {

    /** 항목 하나에 붙이는 자료 구간 상한. 총량은 예산이 정한다. */
    static final int MAX_SECTIONS_PER_TOPIC = 2;
    static final int MAX_UNLINKED_SECTIONS = 8;
    static final int EXCERPT_CHARS = 90;

    /**
     * 프로젝트당 학습 항목·구간 줄의 글자 예산. 2026-09-15 실측(gpt-5.6-luna, 한국어 프롬프트)에서 프롬프트
     * 글자 수 ÷ 입력 토큰 ≈ 1.6이었다 — 12,000자면 과목당 약 7,500토큰, 여섯 과목이어도 5만 토큰 안이다.
     * 실데이터의 가장 큰 과목(항목 77개 + 구간)은 약 4,000자라 이 값에 걸리지 않는다.
     */
    @Value("${plan.draft.context-budget-chars-per-course:12000}")
    private int budgetChars = 12000;

    /** 테스트에서 예산을 줄여 "넘치면 어떻게 되나"를 본다. */
    public void setBudgetChars(int budgetChars) {
        this.budgetChars = budgetChars;
    }

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
     * @param firstUnlearned 표식·제외를 뺀 뒤 첫 NOT_STARTED 항목
     * @param assignmentDue  이 항목에 연결된 확정·미완료 과제 중 가장 이른 마감. 마감이 없거나 연결된 과제가 없으면 null
     * @param assignmentLinked 확정·미완료 과제가 연결돼 있다(마감이 없어도 true)
     * @param userLinked     사용자가 직접 자료를 연결한 항목(topic_material_links.origin = USER)
     */
    public record TopicLine(Long topicId, Long parentTopicId, String title, String locator, Long sourceMaterialId,
                            String sourceMaterialFilename, int depth, TopicProgressStatus progress,
                            TopicUserMark mark, LocalDateTime lastStudiedAt, boolean firstUnlearned,
                            LocalDate assignmentDue, boolean assignmentLinked, boolean userLinked) {

        /** 예전 모양(과제·사용자 연결 없음). 단위 테스트가 쓴다. */
        public TopicLine(Long topicId, Long parentTopicId, String title, String locator, Long sourceMaterialId,
                         String sourceMaterialFilename, int depth, TopicProgressStatus progress,
                         TopicUserMark mark, LocalDateTime lastStudiedAt, boolean firstUnlearned) {
            this(topicId, parentTopicId, title, locator, sourceMaterialId, sourceMaterialFilename, depth, progress,
                    mark, lastStudiedAt, firstUnlearned, null, false, false);
        }

        /** 반드시 포함해야 하는 항목인가 — 예산과 무관하게 판단 입력에 들어간다. */
        public boolean mustInclude() {
            return progress == TopicProgressStatus.IN_PROGRESS || firstUnlearned || assignmentLinked || userLinked;
        }

        TopicLine withFirstUnlearned(boolean value) {
            return new TopicLine(topicId, parentTopicId, title, locator, sourceMaterialId, sourceMaterialFilename,
                    depth, progress, mark, lastStudiedAt, value, assignmentDue, assignmentLinked, userLinked);
        }

        TopicLine withLinks(LocalDate due, boolean assignment, boolean user) {
            return new TopicLine(topicId, parentTopicId, title, locator, sourceMaterialId, sourceMaterialFilename,
                    depth, progress, mark, lastStudiedAt, firstUnlearned, due, assignment, user);
        }
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

    /**
     * @param budgetHidden 예산에 걸려 싣지 못한 항목 수(표식·이번만 제외는 별도)
     * @param mustIncluded 반드시 포함 규칙으로 들어간 항목 수(조상 제외)
     * @param overBudget   반드시 포함만으로 예산을 넘었다 — 그래도 전부 실었다
     */
    public record CourseBundle(List<TopicLine> topics, int excludedByMark, int excludedThisTime, int totalTopics,
                               Map<Long, List<SectionLine>> sectionsByTopic, List<SectionLine> unlinkedSections,
                               List<AssignmentLine> assignments, int completedAssignments,
                               List<PendingMaterial> pendingMaterials, int budgetHidden, int mustIncluded,
                               boolean overBudget) {
    }

    @Transactional(readOnly = true)
    public CourseBundle build(Long userId, Long courseId, Set<Long> excludeTopicIds) {
        List<TopicLine> all = new ArrayList<>();
        for (TopicResponse root : topicService.getTopicTree(userId, courseId)) {
            flatten(all, root, 0);
        }
        List<CourseAssignment> assignmentRows = assignmentService.findByCourses(userId, List.of(courseId));
        Map<Long, LocalDate> dueByTopic = new HashMap<>();
        Set<Long> assignmentTopicIds = new HashSet<>();
        for (CourseAssignment a : assignmentRows) {
            if (a.getConfirmStatus() != AssignmentConfirmStatus.CONFIRMED || a.isCompleted() || a.getTopicId() == null) {
                continue;
            }
            assignmentTopicIds.add(a.getTopicId());
            LocalDate due = a.dueDay();
            if (due != null) {
                dueByTopic.merge(a.getTopicId(), due, (x, y) -> x.isBefore(y) ? x : y);
            }
        }
        List<TopicMaterialLink> courseLinks = topicLinkMapper.findActiveByCourseId(courseId, userId);
        Set<Long> userLinkedTopicIds = courseLinks.stream()
                .filter(l -> l.getOrigin() == TopicLinkOrigin.USER)
                .map(TopicMaterialLink::getTopicId).collect(Collectors.toSet());
        for (int i = 0; i < all.size(); i++) {
            TopicLine line = all.get(i);
            boolean assignment = assignmentTopicIds.contains(line.topicId());
            boolean user = userLinkedTopicIds.contains(line.topicId());
            if (assignment || user) {
                all.set(i, line.withLinks(dueByTopic.get(line.topicId()), assignment, user));
            }
        }

        // 구간은 선택 전에 전부 찾는다 — 예산은 항목 줄과 그 구간 줄을 합친 글자 수로 센다.
        Map<Long, List<SectionLine>> sectionsAll = sectionsFor(userId, all);
        Selection selection = select(all, excludeTopicIds == null ? Set.of() : excludeTopicIds,
                line -> lineCost(line, sectionsAll.getOrDefault(line.topicId(), List.of())), budgetChars);
        if (selection.overBudget()) {
            log.info("계획 입력: 반드시 포함 항목만으로 예산을 넘었다. courseId={}, 글자={}/{}, 항목={}",
                    courseId, selection.usedChars(), budgetChars, selection.lines().size());
        }

        Map<Long, List<SectionLine>> sectionsByTopic = new LinkedHashMap<>();
        for (TopicLine line : selection.lines()) {
            List<SectionLine> mine = sectionsAll.get(line.topicId());
            if (mine != null && !mine.isEmpty()) {
                sectionsByTopic.put(line.topicId(), mine);
            }
        }
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
                sectionsByTopic, unlinked, assignments, completed, pendingOf(userId, courseId, materials),
                selection.budgetHidden(), selection.mustIncluded(), selection.overBudget());
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

    record Selection(List<TopicLine> lines, int excludedByMark, int excludedThisTime, int mustIncluded,
                     int budgetHidden, int usedChars, boolean overBudget) {
    }

    /**
     * 두 단계 선택. 1) 반드시 포함(진행 중·첫 미학습·과제 연결·사용자 연결)과 그 조상은 예산과 무관하게.
     * 2) 나머지는 트리 순서로 예산이 허락하는 만큼 — 첫 미학습 이후(아직 배우지 않은 쪽)를 먼저 채우고,
     * 남으면 그 앞(학습 완료 등)을 채운다. 어느 쪽이든 안 들어가는 항목을 처음 만나면 그 방향은 멈춘다(순서를
     * 건너뛰어 작은 것만 골라 넣지 않는다 — "여기까지 실었다"가 설명 가능해야 한다). 출력은 항상 트리 순서다.
     *
     * @param cost 항목 한 줄(붙는 구간 줄 포함)의 글자 수 추정
     */
    static Selection select(List<TopicLine> all, Set<Long> excludeTopicIds, ToIntFunction<TopicLine> cost,
                            int budgetChars) {
        int excludedByMark = 0;
        int excludedThisTime = 0;
        List<TopicLine> eligible = new ArrayList<>();
        int firstUnlearnedIndex = -1;
        for (TopicLine line : all) {
            if (line.mark() == TopicUserMark.KNOWN || line.mark() == TopicUserMark.DEFER) {
                excludedByMark++;
                continue;
            }
            if (excludeTopicIds.contains(line.topicId())) {
                excludedThisTime++;
                continue;
            }
            boolean first = firstUnlearnedIndex < 0 && line.progress() == TopicProgressStatus.NOT_STARTED;
            if (first) {
                firstUnlearnedIndex = eligible.size();
            }
            eligible.add(first ? line.withFirstUnlearned(true) : line);
        }
        Map<Long, TopicLine> byId = new LinkedHashMap<>();
        for (TopicLine line : eligible) {
            byId.put(line.topicId(), line);
        }

        // 1단계: 반드시 포함 + 조상. 예산을 보지 않는다.
        Set<Long> chosen = new LinkedHashSet<>();
        int used = 0;
        int mustIncluded = 0;
        for (TopicLine line : eligible) {
            if (line.mustInclude()) {
                mustIncluded++;
                used += addWithAncestors(chosen, line, byId, cost);
            }
        }
        boolean overBudget = used > budgetChars;

        // 2단계: 첫 미학습 이후를 순서대로, 그 다음 그 앞을 순서대로. 안 들어가는 항목에서 그 방향을 멈춘다.
        int start = Math.max(firstUnlearnedIndex, 0);
        used = fill(eligible, start, eligible.size(), chosen, byId, cost, budgetChars, used);
        used = fill(eligible, 0, start, chosen, byId, cost, budgetChars, used);

        List<TopicLine> ordered = eligible.stream().filter(l -> chosen.contains(l.topicId())).toList();
        return new Selection(ordered, excludedByMark, excludedThisTime, mustIncluded,
                eligible.size() - ordered.size(), used, overBudget);
    }

    private static int fill(List<TopicLine> eligible, int from, int to, Set<Long> chosen, Map<Long, TopicLine> byId,
                            ToIntFunction<TopicLine> cost, int budgetChars, int used) {
        for (int i = from; i < to; i++) {
            TopicLine line = eligible.get(i);
            if (chosen.contains(line.topicId())) {
                continue;
            }
            int need = chainCost(chosen, line, byId, cost);
            if (used + need > budgetChars) {
                return used;
            }
            used += addWithAncestors(chosen, line, byId, cost);
        }
        return used;
    }

    /** 항목과 아직 안 들어간 조상을 넣고, 그만큼의 글자 수를 돌려준다. */
    private static int addWithAncestors(Set<Long> chosen, TopicLine line, Map<Long, TopicLine> byId,
                                        ToIntFunction<TopicLine> cost) {
        int added = 0;
        for (TopicLine one : chain(chosen, line, byId)) {
            chosen.add(one.topicId());
            added += cost.applyAsInt(one);
        }
        return added;
    }

    private static int chainCost(Set<Long> chosen, TopicLine line, Map<Long, TopicLine> byId,
                                 ToIntFunction<TopicLine> cost) {
        int sum = 0;
        for (TopicLine one : chain(chosen, line, byId)) {
            sum += cost.applyAsInt(one);
        }
        return sum;
    }

    /** 항목에서 뿌리 쪽으로 올라가며 아직 안 들어간 것들을, 뿌리가 앞에 오도록. */
    private static List<TopicLine> chain(Set<Long> chosen, TopicLine line, Map<Long, TopicLine> byId) {
        List<TopicLine> out = new ArrayList<>();
        TopicLine cursor = line;
        int guard = 0;
        while (cursor != null && guard++ < 100) {
            if (!chosen.contains(cursor.topicId())) {
                out.add(0, cursor);
            }
            cursor = cursor.parentTopicId() == null ? null : byId.get(cursor.parentTopicId());
        }
        return out;
    }

    /**
     * 프롬프트에 실릴 항목 줄 + 구간 줄의 글자 수 추정. 정확한 렌더링이 아니라 예산 계산용이다 —
     * 표시(진행 중·첫 미학습·과제·사용자 연결)와 발췌 자르기(EXCERPT_CHARS)를 반영한다.
     */
    static int lineCost(TopicLine line, List<SectionLine> sections) {
        int chars = 6 + line.depth() * 2 + length(line.title());
        if (line.locator() != null && !line.locator().isBlank()) {
            chars += line.locator().length() + 3;
        }
        if (line.progress() == TopicProgressStatus.IN_PROGRESS) {
            chars += 22;
        } else if (line.progress() == TopicProgressStatus.LEARNED) {
            chars += 8;
        }
        if (line.firstUnlearned()) {
            chars += 10;
        }
        if (line.assignmentLinked()) {
            chars += 16;
        }
        if (line.userLinked()) {
            chars += 14;
        }
        for (SectionLine section : sections) {
            MaterialSection s = section.section();
            chars += 14 + line.depth() * 2 + length(section.roleLabels()) + length(s.getDisplayTitle())
                    + length(s.locator()) + 3
                    + (s.getTaskText() == null ? 0 : Math.min(s.getTaskText().length(), EXCERPT_CHARS) + 6)
                    + (s.getExcerpt() == null ? 0 : Math.min(s.getExcerpt().length(), EXCERPT_CHARS) + 4);
        }
        return chars;
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
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
            // 문제·예제를 앞에. 서버가 "무엇을 먼저 할지"를 정하는 것이 아니라, 항목당 상한에 걸릴 때 남길 것을 정한다.
            mine.sort(Comparator.comparingInt(s -> rolePriority(s.roles())));
            if (!mine.isEmpty()) {
                out.put(line.topicId(), new ArrayList<>(mine.subList(0, Math.min(mine.size(), MAX_SECTIONS_PER_TOPIC))));
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
