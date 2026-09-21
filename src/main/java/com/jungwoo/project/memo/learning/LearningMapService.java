package com.jungwoo.project.memo.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로젝트 전체 학습 지도(읽기 전용).
 *
 * <p>지도의 뿌리는 자료 파일이 아니라 <b>학습 항목</b>이다 — 자료마다 따로 트리가 있는 것처럼 보이던 것을, 한 프로젝트의
 * 구조 안에서 여러 자료가 어디에 연결되는지 보이게 한다. 승인 전 구조 제안은 같은 구조 안의 미리보기로 얹는다.
 *
 * <p>이 서비스는 아무것도 쓰지 않는다. 지도를 본다고 학습 항목·진도·연결이 생기지 않고, 지도를 정리하는 일은 상담·계획의
 * 선행 조건이 아니다.
 *
 * <p>주차는 <b>자료가 스스로 말한 것</b>만 보여 준다("3주차"라는 표기). 파일 번호를 주차로 읽지 않고, 개강일에서 날짜 계산으로
 * 만들지도 않는다 — 실제 수업 진행·개인 진도와는 다를 수 있어 confirmed=false로만 낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMapService {

    private static final Pattern WEEK = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*주\\s*차");

    private final CourseMapper courseMapper;
    private final TopicService topicService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialSectionMapper sectionMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final MaterialAnalysisStatusService analysisStatusService;
    private final MaterialAnalysisJobService jobService;
    private final ProposedTopicIndex proposedTopicIndex;
    private final ExecutionItemMapper executionItemMapper;
    private final UserContextMapper userContextMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        List<LearningMapResponse.TopicNode> topics = new ArrayList<>();
        for (TopicResponse root : tree) {
            topics.add(node(root, itemCounts, selfCheckByTopic, topicCount));
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

        return new LearningMapResponse(courseId, course.getTitle(), course.getTopicTreeVersion(),
                new LearningMapResponse.State(materials.size(), pending, failed, linkWaiting, proposed.size(),
                        topicCount[0], hasRecords),
                topics, proposed, unlinked, weeks(userId, materials, sectionById, tree));
    }

    private LearningMapResponse.TopicNode node(TopicResponse t, Map<Long, int[]> counts, Map<Long, String> selfChecks,
                                               int[] topicCount) {
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
            children.add(node(child, counts, selfChecks, topicCount));
        }
        return new LearningMapResponse.TopicNode(t.getTopicId(), t.getParentTopicId(), t.getTitle(),
                t.getProgressStatus() == null ? null : t.getProgressStatus().name(),
                t.getUserMark() == null ? null : t.getUserMark().name(), selfChecks.get(t.getTopicId()), refs, c[0], c[1],
                children);
    }

    /** 자료가 스스로 말한 주차: 문서 메타의 weekLabel과 구간 제목의 "N주차" 표기. */
    private List<LearningMapResponse.Week> weeks(Long userId, Map<Long, CourseMaterial> materials,
                                                 Map<Long, MaterialSection> sectionById, List<TopicResponse> tree) {
        Map<Integer, LearningMapResponse.Week> byWeek = new java.util.TreeMap<>();
        if (!materials.isEmpty()) {
            Map<Long, MaterialAnalysisJob> latest = new HashMap<>();
            for (MaterialAnalysisJob job : jobService.findByMaterials(userId, new ArrayList<>(materials.keySet()))) {
                CourseMaterial m = materials.get(job.getMaterialId());
                if (job.getJobKind() == AnalysisJobKind.CONTENT && m != null
                        && java.util.Objects.equals(m.getFileHash(), job.getFileHash())) {
                    latest.merge(job.getMaterialId(), job, (a, b) -> a.getJobId() > b.getJobId() ? a : b);
                }
            }
            for (MaterialAnalysisJob job : latest.values()) {
                Integer week = weekOf(weekLabelOf(job.getCheckpointJson()));
                if (week != null) {
                    week(byWeek, week).materialIds().add(job.getMaterialId());
                }
            }
        }
        for (MaterialSection s : sectionById.values()) {
            Integer week = weekOf(s.getDisplayTitle());
            if (week == null) {
                week = weekOf(s.getSectionLabel());
            }
            if (week != null) {
                week(byWeek, week).sectionIds().add(s.getSectionId());
            }
        }
        if (byWeek.isEmpty()) {
            return List.of();
        }
        // 그 주차의 자료·구간에 연결된 학습 항목.
        for (LearningMapResponse.Week w : byWeek.values()) {
            collectTopics(tree, w);
        }
        return new ArrayList<>(byWeek.values());
    }

    private static void collectTopics(List<TopicResponse> nodes, LearningMapResponse.Week week) {
        for (TopicResponse t : nodes == null ? List.<TopicResponse>of() : nodes) {
            boolean linked = t.getLinkedMaterials() != null && t.getLinkedMaterials().stream().anyMatch(lm ->
                    (lm.getSectionId() != null && week.sectionIds().contains(lm.getSectionId()))
                            || week.materialIds().contains(lm.getMaterialId()));
            if (linked && !week.topicIds().contains(t.getTopicId())) {
                week.topicIds().add(t.getTopicId());
            }
            collectTopics(t.getChildren(), week);
        }
    }

    private static LearningMapResponse.Week week(Map<Integer, LearningMapResponse.Week> byWeek, int n) {
        return byWeek.computeIfAbsent(n, k -> new LearningMapResponse.Week(k + "주차", "MATERIAL_LABEL", false,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
    }

    private String weekLabelOf(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(checkpointJson).get("weekLabel");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    static Integer weekOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WEEK.matcher(text);
        if (!m.find()) {
            return null;
        }
        int week = Integer.parseInt(m.group(1));
        return week >= 1 && week <= 30 ? week : null;
    }

    private static LearningMapResponse.SectionRef sectionRef(MaterialSection s) {
        return new LearningMapResponse.SectionRef(s.getSectionId(), s.getDisplayTitle(), s.locator());
    }
}
