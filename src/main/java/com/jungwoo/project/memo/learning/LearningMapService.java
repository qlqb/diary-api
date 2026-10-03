package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.learning.dto.LearningMapResponse;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.learning.structure.ProposedTopicIndex;
import com.jungwoo.project.memo.learning.week.MaterialWeekAssignment;
import com.jungwoo.project.memo.learning.week.MaterialWeekAssignmentMapper;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트 전체 학습 지도(읽기 전용).
 *
 * <p>지도의 뿌리는 자료 파일이 아니라 <b>학습 항목</b>이다 — 자료마다 따로 트리가 있는 것처럼 보이던 것을, 한 프로젝트의
 * 구조 안에서 여러 자료가 어디에 연결되는지 보이게 한다. 승인 전 구조 제안은 같은 구조 안의 미리보기로 얹는다.
 *
 * <p>이 서비스는 아무것도 쓰지 않는다. 지도를 본다고 학습 항목·진도·연결이 생기지 않고, 지도를 정리하는 일은 상담·계획의
 * 선행 조건이 아니다.
 *
 * <p>주차는 <b>사용자가 확인한 자료 ↔ 주차 관계</b>(material_week_assignments)만으로 만든다. 학습 항목의 연결을 뒤져
 * 주차를 역추론하지 않는다 — 예전에는 구간 제목·분석 메타의 "N주차"를 읽어서, 강의계획서의 예정 진도("1~3주차 오리엔테이션")가
 * 실제 주차 칸을 차지하고 "3주차"라고 적지 않은 실제 강의 자료는 어느 주차에도 들어가지 못했다. 추천(확인 전)도 지도에 넣지
 * 않는다. 추천은 자료 주차 확인 화면({@link com.jungwoo.project.memo.learning.week.MaterialWeekService})의 몫이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMapService {

    private static final Set<String> ANALYSIS_IN_PROGRESS = Set.of("QUEUED", "RUNNING");

    private final CourseMapper courseMapper;
    private final TopicService topicService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialSectionMapper sectionMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final MaterialAnalysisStatusService analysisStatusService;
    private final ProposedTopicIndex proposedTopicIndex;
    private final ExecutionItemMapper executionItemMapper;
    private final UserContextMapper userContextMapper;
    private final MaterialWeekAssignmentMapper weekAssignmentMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jungwoo.project.memo.learning.correction.CourseCorrectionMapper correctionMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CourseTopicMapper topicMapper;

    @Transactional(readOnly = true)
    public LearningMapResponse map(Long userId, Long courseId) {
        List<Course> owned = courseMapper.findByIdsAndUserId(List.of(courseId), userId);
        if (owned == null || owned.isEmpty()) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        Course course = owned.get(0);

        Map<Long, CourseMaterial> materials = new LinkedHashMap<>();
        for (CourseMaterial m : courseMaterialMapper.findByCourseIdAndUserId(courseId, userId)) {
            materials.put(m.getMaterialId(), m);
        }
        List<MaterialSection> sections = materials.isEmpty() ? List.of()
                : sectionMapper.findActiveByMaterialIds(new ArrayList<>(materials.keySet()), userId);
        Map<Long, MaterialSection> sectionById = new HashMap<>();
        for (MaterialSection s : sections) {
            CourseMaterial m = materials.get(s.getMaterialId());
            if (m != null && s.getExcerpt() != null && java.util.Objects.equals(m.getFileHash(), s.getFileHash())) {
                sectionById.put(s.getSectionId(), s);
            }
        }

        // 실행 항목·자기평가는 학습 항목에 얹는 표시일 뿐이다.
        Map<Long, int[]> itemCounts = new HashMap<>();
        boolean hasRecords = false;
        for (ExecutionItem item : executionItemMapper.findByUserIdAndCourseId(userId, courseId, null, null)) {
            if (item.getStatus() == ExecutionStatus.DONE || item.getStatus() == ExecutionStatus.PARTIAL) {
                hasRecords = true;
            }
            if (item.getTopicId() == null || item.getStatus() == ExecutionStatus.CANCELLED) {
                continue;
            }
            int[] c = itemCounts.computeIfAbsent(item.getTopicId(), k -> new int[2]);
            c[0]++;
            if (item.getStatus() == ExecutionStatus.DONE) {
                c[1]++;
            }
        }
        Map<Long, String> selfCheckByTopic = new HashMap<>();
        for (UserContext check : userContextMapper.findActiveSelfChecks(userId, courseId)) {
            if (check.getTopicId() != null && check.getSelfLevel() != null) {
                selfCheckByTopic.putIfAbsent(check.getTopicId(), check.getSelfLevel());
            }
        }

        List<TopicResponse> tree = topicService.getTopicTree(userId, courseId);
        int[] topicCount = {0};
        Set<Long> linkedSectionIds = new HashSet<>();
        Set<Long> wholeLinkedMaterials = new HashSet<>();
        for (var link : topicLinkMapper.findActiveByCourseId(courseId, userId)) {
            if (link.getSectionId() != null && link.getSectionId() != com.jungwoo.project.memo.learning.domain.TopicMaterialLink.WHOLE_MATERIAL) {
                linkedSectionIds.add(link.getSectionId());
            } else {
                wholeLinkedMaterials.add(link.getMaterialId());
            }
        }
        // 사용자가 정정한 실제 수업 진행·범위 제외, 그리고 병합으로 보관된 항목에 남은 기록(옮기지 않고 알리기만 한다).
        Map<Long, com.jungwoo.project.memo.learning.correction.TopicClassProgress> classByTopic = new HashMap<>();
        Map<Long, String> scopeByTopic = new HashMap<>();
        if (correctionMapper != null) {
            for (var row : correctionMapper.findClassProgress(courseId, userId)) {
                classByTopic.put(row.getTopicId(), row);
            }
            for (var row : correctionMapper.findActiveExclusions(courseId, userId)) {
                scopeByTopic.putIfAbsent(row.getTopicId(), row.getLabel() == null ? "" : row.getLabel());
            }
        }
        Map<Long, Integer> mergedDone = new HashMap<>();
        Map<Long, Long> mergedInto = new HashMap<>();
        for (var t : topicMapper == null ? List.<com.jungwoo.project.memo.learning.domain.CourseTopic>of()
                : topicMapper.findByCourseIdAndUserIdIncludingArchived(courseId, userId)) {
            if (t.getMergedIntoTopicId() != null) {
                mergedInto.put(t.getTopicId(), t.getMergedIntoTopicId());
            }
        }
        for (Map.Entry<Long, Long> e : mergedInto.entrySet()) {
            Long target = e.getValue();
            int guard = 0;
            while (mergedInto.containsKey(target) && guard++ < 20) {
                target = mergedInto.get(target);
            }
            int[] c = itemCounts.get(e.getKey());
            if (c != null && c[1] > 0) {
                mergedDone.merge(target, c[1], Integer::sum);
            }
        }
        Extras extras = new Extras(classByTopic, scopeByTopic, mergedDone,
                com.jungwoo.project.memo.course.textbook.BookKey.of(course));
        List<LearningMapResponse.TopicNode> topics = new ArrayList<>();
        for (TopicResponse root : tree) {
            topics.add(node(root, itemCounts, selfCheckByTopic, topicCount, extras));
        }

        // 승인 전 제안(읽기 전용)
        ProposedTopicIndex.Index index = proposedTopicIndex.forCourse(userId, courseId, materials);
        List<LearningMapResponse.ProposedGroup> proposed = new ArrayList<>();
        Set<Long> proposedSectionIds = new HashSet<>();
        Map<Long, List<ProposedTopicIndex.Node>> nodesByProposal = new LinkedHashMap<>();
        for (ProposedTopicIndex.Node n : index.nodes()) {
            nodesByProposal.computeIfAbsent(n.proposalIds().get(0), k -> new ArrayList<>()).add(n);
            proposedSectionIds.addAll(n.sectionIds());
        }
        for (var proposal : index.proposals()) {
            List<ProposedTopicIndex.Node> nodes = nodesByProposal.getOrDefault(proposal.getProposalId(), List.of());
            if (nodes.isEmpty()) {
                continue;
            }
            /*
             * (2026-09-21) 정리안은 이제 프로젝트 단위라 "이 제안의 자료" 하나가 없다. 근거 자료가
             * 여럿인 것이 요점이므로, 묶음 머리에는 몇 개를 함께 봤는지를 적는다.
             */
            List<Long> evidenceMaterialIds = nodes.stream().flatMap(n -> n.materialIds().stream())
                    .distinct().toList();
            String filename = evidenceMaterialIds.size() == 1
                    ? materials.containsKey(evidenceMaterialIds.get(0))
                        ? materials.get(evidenceMaterialIds.get(0)).getOriginalFilename() : null
                    : evidenceMaterialIds.isEmpty() ? null : "자료 " + evidenceMaterialIds.size() + "개";
            proposed.add(new LearningMapResponse.ProposedGroup(proposal.getProposalId(),
                    evidenceMaterialIds.size() == 1 ? evidenceMaterialIds.get(0) : null,
                    filename,
                    nodes.stream().map(n -> new LearningMapResponse.ProposedNode(n.nodeId(), n.title(), n.parentNodeId(),
                            n.parentTopicId(), n.op(), n.reason(),
                            n.sectionIds().stream().map(sectionById::get).filter(java.util.Objects::nonNull)
                                    .map(LearningMapService::sectionRef).toList())).toList()));
        }

        // 분석 상태와 아직 어디에도 연결되지 않은 자료
        Map<Long, MaterialAnalysisStatusResponse> status = new HashMap<>();
        for (MaterialAnalysisStatusResponse s : analysisStatusService.statuses(userId, new ArrayList<>(materials.values()))) {
            status.put(s.getMaterialId(), s);
        }
        int pending = 0;
        int failed = 0;
        int linkWaiting = 0;
        List<LearningMapResponse.UnlinkedMaterial> unlinked = new ArrayList<>();
        for (CourseMaterial m : materials.values()) {
            MaterialAnalysisStatusResponse st = status.get(m.getMaterialId());
            String state = st == null ? "NONE" : st.getState();
            if (List.of("QUEUED", "RUNNING", "NONE", "PAUSED").contains(state)) {
                pending++;
            } else if (List.of("FAILED", "UNAVAILABLE", "NO_TEXT").contains(state)) {
                failed++;
            }
            if (st != null && st.getLinkState() != null && List.of("QUEUED", "RUNNING", "PAUSED").contains(st.getLinkState())) {
                linkWaiting++;
            }
            List<LearningMapResponse.SectionRef> free = new ArrayList<>();
            for (MaterialSection s : sectionById.values()) {
                if (s.getMaterialId().equals(m.getMaterialId()) && !linkedSectionIds.contains(s.getSectionId())
                        && !proposedSectionIds.contains(s.getSectionId())) {
                    free.add(sectionRef(s));
                }
            }
            boolean anyLinked = wholeLinkedMaterials.contains(m.getMaterialId()) || sectionById.values().stream()
                    .anyMatch(s -> s.getMaterialId().equals(m.getMaterialId()) && (linkedSectionIds.contains(s.getSectionId())
                            || proposedSectionIds.contains(s.getSectionId())));
            if (!free.isEmpty() || !anyLinked) {
                unlinked.add(new LearningMapResponse.UnlinkedMaterial(m.getMaterialId(), m.getOriginalFilename(), state,
                        st == null ? null : st.getWaitingReason(), free));
            }
        }

        // 확인된 자료 ↔ 주차 관계. 추천은 여기 싣지 않는다.
        List<MaterialWeekAssignment> assignments = weekAssignmentMapper.findActiveByCourse(userId, courseId);
        Set<Long> placed = new HashSet<>();
        List<LearningMapResponse.WeekMaterial> courseWide = new ArrayList<>();
        for (MaterialWeekAssignment a : assignments) {
            if (materials.containsKey(a.getMaterialId()) && placed.add(a.getMaterialId())
                    && WeekSuggestionEngine.Placement.COURSE_WIDE.name().equals(a.getPlacement())) {
                courseWide.add(new LearningMapResponse.WeekMaterial(a.getMaterialId(),
                        materials.get(a.getMaterialId()).getOriginalFilename()));
            }
        }
        int needsReview = 0;
        for (CourseMaterial m : materials.values()) {
            MaterialAnalysisStatusResponse st = status.get(m.getMaterialId());
            if (!placed.contains(m.getMaterialId()) && (st == null || !ANALYSIS_IN_PROGRESS.contains(st.getState()))) {
                needsReview++;
            }
        }

        return new LearningMapResponse(courseId, course.getTitle(), course.getTopicTreeVersion(),
                new LearningMapResponse.State(materials.size(), pending, failed, linkWaiting, proposed.size(),
                        topicCount[0], hasRecords),
                topics, proposed, unlinked, withClassWeeks(weeks(assignments, materials, sectionById, tree), classByTopic),
                new LearningMapResponse.WeekReview(needsReview, placed.size(), courseWide),
                com.jungwoo.project.memo.course.textbook.TextbookFacts.identity(course.getTextbookTitle(),
                        course.getTextbookIsbn(), course.getTextbookEdition(), course.getTextbookPublisher(),
                        course.getTextbookAuthor(), course.getTextbookInfoSource()));
    }

    record Extras(Map<Long, com.jungwoo.project.memo.learning.correction.TopicClassProgress> classByTopic,
                  Map<Long, String> scopeByTopic, Map<Long, Integer> mergedDone, String currentBookKey) {
        static final Extras NONE = new Extras(Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * 사용자가 실제 수업 주차를 정정한 항목을 그 주차에 싣는다. 자료로 정해진 주차와 합치고, 자료 없이 정정만 있는 주차도
     * 만든다(근거: CONFIRMED_CLASS). 교재 위치는 바꾸지 않는다.
     */
    static List<LearningMapResponse.Week> withClassWeeks(List<LearningMapResponse.Week> weeks,
                                                         Map<Long, com.jungwoo.project.memo.learning.correction.TopicClassProgress> classByTopic) {
        if (classByTopic.isEmpty()) {
            return weeks;
        }
        Map<Integer, LearningMapResponse.Week> byWeek = new java.util.TreeMap<>();
        weeks.forEach(w -> byWeek.put(w.weekNo(), w));
        for (var row : classByTopic.values()) {
            if (row.getWeekNo() == null) {
                continue;
            }
            LearningMapResponse.Week w = byWeek.get(row.getWeekNo());
            if (w == null) {
                byWeek.put(row.getWeekNo(), new LearningMapResponse.Week(row.getWeekNo() + "주차", "CONFIRMED_CLASS", true,
                        new ArrayList<>(), new ArrayList<>(), new ArrayList<>(List.of(row.getTopicId())), row.getWeekNo(),
                        new ArrayList<>()));
            } else if (!w.topicIds().contains(row.getTopicId())) {
                List<Long> ids = new ArrayList<>(w.topicIds());
                ids.add(row.getTopicId());
                byWeek.put(row.getWeekNo(), new LearningMapResponse.Week(w.label(), w.basis(), w.confirmed(),
                        w.materialIds(), w.sectionIds(), ids, w.weekNo(), w.materials()));
            }
        }
        return new ArrayList<>(byWeek.values());
    }

    private LearningMapResponse.TopicNode node(TopicResponse t, Map<Long, int[]> counts, Map<Long, String> selfChecks,
                                               int[] topicCount) {
        return node(t, counts, selfChecks, topicCount, Extras.NONE);
    }

    private LearningMapResponse.TopicNode node(TopicResponse t, Map<Long, int[]> counts, Map<Long, String> selfChecks,
                                               int[] topicCount, Extras extras) {
        topicCount[0]++;
        int[] c = counts.getOrDefault(t.getTopicId(), new int[2]);
        List<LearningMapResponse.MaterialRef> refs = new ArrayList<>();
        for (TopicResponse.LinkedMaterial lm : t.getLinkedMaterials() == null ? List.<TopicResponse.LinkedMaterial>of()
                : t.getLinkedMaterials()) {
            if (!lm.isMaterialDeleted()) {
                refs.add(new LearningMapResponse.MaterialRef(lm.getMaterialId(), lm.getFilename(), lm.getSectionId(),
                        lm.getSectionTitle(), lm.getLocator()));
            }
        }
        List<LearningMapResponse.TopicNode> children = new ArrayList<>();
        for (TopicResponse child : t.getChildren() == null ? List.<TopicResponse>of() : t.getChildren()) {
            children.add(node(child, counts, selfChecks, topicCount, extras));
        }
        var klass = extras.classByTopic().get(t.getTopicId());
        return new LearningMapResponse.TopicNode(t.getTopicId(), t.getParentTopicId(), t.getTitle(),
                t.getProgressStatus() == null ? null : t.getProgressStatus().name(),
                t.getUserMark() == null ? null : t.getUserMark().name(), selfChecks.get(t.getTopicId()), refs, c[0], c[1],
                children, klass == null ? null : klass.getWeekNo(), klass == null ? null : klass.getClassSeq(),
                extras.scopeByTopic().get(t.getTopicId()), extras.mergedDone().getOrDefault(t.getTopicId(), 0),
                t.getSourceType() == null ? null : t.getSourceType().name(), t.getSourceLocator(),
                t.getSourceWebRevisionId() != null ? "WEB"
                        : t.getSourceTextbookKey() != null || isTocLocator(t.getSourceLocator()) ? "MATERIAL" : null,
                extras.currentBookKey() != null && t.getSourceTextbookKey() != null
                        && !extras.currentBookKey().equals(t.getSourceTextbookKey()));
    }

    private static boolean isTocLocator(String locator) {
        return locator != null && (locator.startsWith("교재 p.") || locator.equals("교재 목차"));
    }

    /**
     * 확인된 주차: 주차 → 그 주차에 놓인 자료 → 그 자료의 구간 → 그 자료와 직접 연결된 학습 항목.
     *
     * <p>한 항목이 여러 주차의 자료에 나오면 여러 주차에 나타나는 것이 맞다. 다만 상위 항목이라는 이유만으로는
     * 나타나지 않는다 — 같은 구간이 상위·하위 항목에 함께 연결돼 있으면 더 구체적인 하위 항목만 싣는다. 예전에는
     * 상위 항목이 하위 항목의 주차마다 되풀이됐다.
     */
    static List<LearningMapResponse.Week> weeks(List<MaterialWeekAssignment> assignments,
                                                Map<Long, CourseMaterial> materials,
                                                Map<Long, MaterialSection> sectionById, List<TopicResponse> tree) {
        Map<Integer, Set<Long>> materialsByWeek = new java.util.TreeMap<>();
        for (MaterialWeekAssignment a : assignments) {
            if (WeekSuggestionEngine.Placement.WEEK.name().equals(a.getPlacement())
                    && materials.containsKey(a.getMaterialId())) {
                materialsByWeek.computeIfAbsent(a.getWeekNo(), k -> new java.util.LinkedHashSet<>()).add(a.getMaterialId());
            }
        }
        List<LearningMapResponse.Week> weeks = new ArrayList<>();
        for (Map.Entry<Integer, Set<Long>> e : materialsByWeek.entrySet()) {
            Set<Long> weekMaterials = e.getValue();
            List<Long> sectionIds = sectionById.values().stream()
                    .filter(s -> weekMaterials.contains(s.getMaterialId()))
                    .sorted(java.util.Comparator.comparing(MaterialSection::getMaterialId)
                            .thenComparing(MaterialSection::getUnitStart, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .map(MaterialSection::getSectionId).toList();
            List<LearningMapResponse.WeekMaterial> named = weekMaterials.stream()
                    .map(id -> new LearningMapResponse.WeekMaterial(id, materials.get(id).getOriginalFilename())).toList();
            weeks.add(new LearningMapResponse.Week(e.getKey() + "주차", "CONFIRMED_MATERIAL", true,
                    new ArrayList<>(weekMaterials), sectionIds, topicsFor(tree, weekMaterials), e.getKey(), named));
        }
        return weeks;
    }

    /** 그 주차 자료와 직접 연결된 항목. 근거가 하위 항목에 모두 있으면 상위 항목은 뺀다. */
    static List<Long> topicsFor(List<TopicResponse> tree, Set<Long> weekMaterials) {
        Map<Long, Set<String>> evidence = new LinkedHashMap<>();
        collectEvidence(tree, weekMaterials, evidence);
        List<Long> out = new ArrayList<>();
        keepSpecific(tree, evidence, out);
        return out;
    }

    private static void collectEvidence(List<TopicResponse> nodes, Set<Long> weekMaterials,
                                        Map<Long, Set<String>> evidence) {
        for (TopicResponse t : nodes == null ? List.<TopicResponse>of() : nodes) {
            Set<String> keys = new HashSet<>();
            for (TopicResponse.LinkedMaterial lm : t.getLinkedMaterials() == null
                    ? List.<TopicResponse.LinkedMaterial>of() : t.getLinkedMaterials()) {
                if (lm.isMaterialDeleted() || !weekMaterials.contains(lm.getMaterialId())) {
                    continue;
                }
                boolean whole = lm.getSectionId() == null
                        || lm.getSectionId() == com.jungwoo.project.memo.learning.domain.TopicMaterialLink.WHOLE_MATERIAL;
                keys.add(whole ? "m:" + lm.getMaterialId() : "s:" + lm.getSectionId());
            }
            if (!keys.isEmpty()) {
                evidence.put(t.getTopicId(), keys);
            }
            collectEvidence(t.getChildren(), weekMaterials, evidence);
        }
    }

    /** 트리 순서대로 돌며, 자기 근거가 하위 항목들의 근거에 다 들어 있지 않은 항목만 남긴다. 하위 근거 합을 돌려준다. */
    private static Set<String> keepSpecific(List<TopicResponse> nodes, Map<Long, Set<String>> evidence, List<Long> out) {
        Set<String> subtree = new HashSet<>();
        for (TopicResponse t : nodes == null ? List.<TopicResponse>of() : nodes) {
            int at = out.size();
            Set<String> below = keepSpecific(t.getChildren(), evidence, out);
            Set<String> own = evidence.get(t.getTopicId());
            if (own != null && !below.containsAll(own)) {
                out.add(at, t.getTopicId()); // 부모를 자식보다 앞에 둔다(트리 순서)
            }
            subtree.addAll(below);
            if (own != null) {
                subtree.addAll(own);
            }
        }
        return subtree;
    }

    private static LearningMapResponse.SectionRef sectionRef(MaterialSection s) {
        return new LearningMapResponse.SectionRef(s.getSectionId(), s.getDisplayTitle(), s.locator());
    }
}
