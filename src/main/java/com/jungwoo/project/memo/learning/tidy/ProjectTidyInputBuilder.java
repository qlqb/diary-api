package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.assignment.CourseAssignmentMapper;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.tidy.domain.TidyExcludeReason;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 정리 요청 하나의 <b>입력을 고정한다</b>. 프로젝트의 트리와 연결된 분석 완료 자료를 한 묶음으로
 * 만들고, 무엇을 뺐는지를 사유와 함께 남긴다.
 *
 * <p>왜 스냅샷인가: 요청과 모델 응답 사이에 자료가 더 끝날 수 있다. 그것을 조용히 섞으면
 * 사용자가 승인 화면에서 본 근거와 실제로 쓴 근거가 달라진다. 요청 이후에 끝난 자료는 이번
 * 정리에 들어가지 않고, 화면이 "새 자료 N개를 반영할 수 있어요"로 따로 알린다.
 *
 * <p>입력 한도: 여기서는 자르지 않는다. 모든 구간을 넘기고, 무엇을 목록으로 보고 무엇을 자세히
 * 읽을지는 {@link ProjectTidyReviewPlanner}가 정한다. 예전에는 여기서 글자 예산 안으로 잘랐는데,
 * 그 순서가 입력 순서에 묶여 뒤쪽 자료가 매번 빠졌다.
 *
 * <p>{@link #chooseWithinBudget}는 그 옛 방식이다. 더 이상 입력 경로에서 쓰지 않고, 무엇을 고쳤는지
 * 비교하는 테스트(ProjectTidyReviewPlannerTest, ProjectTidyBudgetTest)만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ProjectTidyInputBuilder {

    /** 구간 한 줄의 최대 길이. 넘으면 수행 내용을 줄인다. */
    private static final int MAX_TASK_CHARS = 140;

    private final CourseTopicMapper topicMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialSectionMapper sectionMapper;
    private final CourseAssignmentMapper assignmentMapper;
    private final MaterialAnalysisStatusService statusService;

    /**
     * 구간 목록에 쓸 글자 예산. 모델 입력 상한을 넘기지 않으면서 프로젝트 하나의 자료를 함께
     * 볼 수 있는 크기다. 넘치면 자르고 잘랐다고 적는다 — 조용히 버리지 않는다.
     */
    @Value("${project.tidy.section-char-budget:40000}")
    private int sectionCharBudget = 40000;

    /** 고정된 입력 한 벌. */
    public record Input(Course course, List<CourseTopic> topics, List<TopicMaterialLink> topicLinks,
                        List<MaterialSection> sections, Map<Long, CourseMaterial> materialsById,
                        List<CourseAssignment> assignments, ProjectTidyScope scope) {

        public boolean isEmpty() {
            return sections.isEmpty();
        }
    }

    /** 요청 화면이 미리 보여 줄 범위(모델을 부르지 않는다). */
    public record Preview(int readyCount, int analyzingCount, int problemCount, int unlinkedSectionMaterials,
                          List<ProjectTidyScope.Excluded> excluded, List<Ready> ready) {

        public record Ready(Long materialId, String filename, int sectionCount, boolean everLinked) {
        }

        public boolean canTidy() {
            return readyCount > 0;
        }
    }

    /**
     * 정리에 쓸 수 있는 자료와 뺄 자료를 가른다. 모델을 부르지 않으므로 화면이 버튼 옆에
     * "분석 완료 5개로 정리 · 분석 중 2개 제외"를 바로 보여줄 수 있다.
     */
    @Transactional(readOnly = true)
    public Preview preview(Long userId, Long courseId) {
        Split split = split(userId, courseId);
        List<Preview.Ready> ready = new ArrayList<>();
        Set<Long> linkedMaterialIds = new HashSet<>();
        for (TopicMaterialLink link : topicLinkMapper.findActiveByCourseId(courseId, userId)) {
            linkedMaterialIds.add(link.getMaterialId());
        }
        int unlinked = 0;
        for (CourseMaterial material : split.ready()) {
            boolean everLinked = linkedMaterialIds.contains(material.getMaterialId());
            if (!everLinked) {
                unlinked++;
            }
            ready.add(new Preview.Ready(material.getMaterialId(), material.getOriginalFilename(),
                    split.sectionCounts().getOrDefault(material.getMaterialId(), 0), everLinked));
        }
        int analyzing = (int) split.excluded().stream()
                .filter(e -> TidyExcludeReason.ANALYZING.name().equals(e.reason())).count();
        int problem = split.excluded().size() - analyzing;
        return new Preview(ready.size(), analyzing, problem, unlinked, split.excluded(), ready);
    }

    /**
     * 실행 시점의 입력. 여기서 고정된 것만 이번 정리에 들어간다.
     *
     * @param allowedMaterialIds 요청 시점에 고정한 자료. null이 아니면 이 안의 자료만 싣는다 —
     *                           요청 뒤에 분석이 끝난 자료가 몰래 섞이지 않게 하는 장치다.
     *                           그 사이 못 쓰게 된 자료는 사유와 함께 제외로 남는다
     */
    @Transactional(readOnly = true)
    public Input build(Long userId, Course course, Set<Long> allowedMaterialIds) {
        Long courseId = course.getCourseId();
        Split split = split(userId, courseId);
        if (allowedMaterialIds != null) {
            split = restrictTo(split, allowedMaterialIds);
        }
        List<CourseTopic> topics = topicMapper.findActiveByCourseIdAndUserId(courseId, userId);
        long treeVersion = course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion();

        Map<Long, CourseMaterial> byId = new LinkedHashMap<>();
        for (CourseMaterial material : split.ready()) {
            byId.put(material.getMaterialId(), material);
        }
        List<MaterialSection> all = byId.isEmpty() ? List.of()
                : sectionMapper.findActiveByMaterialIds(new ArrayList<>(byId.keySet()), userId);

        Set<Long> linkedMaterialIds = new HashSet<>();
        List<TopicMaterialLink> topicLinks = topicLinkMapper.findActiveByCourseId(courseId, userId);
        for (TopicMaterialLink link : topicLinks) {
            linkedMaterialIds.add(link.getMaterialId());
        }
        /*
         * 여기서는 자르지 않는다. 예전에는 글자 예산 안에서 입력 순서대로 잘라 뒤쪽 자료가 매번
         * 빠졌다. 이제 모든 구간을 넘기고, 무엇을 목록으로 보고 무엇을 자세히 읽을지는
         * ProjectTidyReviewPlanner가 정한다. 범위의 수는 그 결과로 finalizeScope가 채운다.
         */
        List<ProjectTidyScope.Member> members = new ArrayList<>();
        List<ProjectTidyScope.Excluded> excluded = new ArrayList<>(split.excluded());
        for (CourseMaterial material : byId.values()) {
            int total = split.sectionCounts().getOrDefault(material.getMaterialId(), 0);
            members.add(new ProjectTidyScope.Member(material.getMaterialId(), material.getOriginalFilename(),
                    material.getFileHash(), first(all, material.getMaterialId()), total, 0, 0));
        }
        ProjectTidyScope scope = new ProjectTidyScope(courseId, treeVersion, topics.size(),
                Math.min(topics.size(), ProjectTidyReviewPlanner.MAX_TREE_LINES), members, excluded,
                topics.size() > ProjectTidyReviewPlanner.MAX_TREE_LINES,
                all.size(), 0, 0, 0);
        List<MaterialSection> chosen = all;

        List<CourseAssignment> assignments = assignmentMapper.findByCourseId(courseId, userId).stream()
                .filter(a -> a.getConfirmStatus() != AssignmentConfirmStatus.NOT_ASSIGNMENT
                        && a.getConfirmStatus() != AssignmentConfirmStatus.DUPLICATE)
                .toList();
        return new Input(course, topics, topicLinks, chosen, byId, assignments, scope);
    }

    /**
     * 무엇을 목록으로 보고 무엇을 자세히 읽었는지로 범위를 채운다.
     *
     * <p>목록에서도 보지 못한 자료(고르기 호출 한도 초과)는 검토 목록에서 빼 제외로 옮긴다 —
     * 사유 "이번에 못 실음". 목록으로만 본 자료는 검토 목록에 남고, 자세히 읽은 수가 따로 적힌다.
     */
    public Input finalizeScope(Input input, ProjectTidyReviewPlanner.Review review) {
        if (review == null) {
            return input;
        }
        ProjectTidyScope scope = input.scope();
        Map<Long, Integer> listed = new HashMap<>();
        Map<Long, Integer> detailed = new HashMap<>();
        Set<Long> detailIds = new HashSet<>(review.detailSectionIds());
        for (MaterialSection s : input.sections()) {
            if (review.listedSectionIds().contains(s.getSectionId())) {
                listed.merge(s.getMaterialId(), 1, Integer::sum);
            }
            if (detailIds.contains(s.getSectionId())) {
                detailed.merge(s.getMaterialId(), 1, Integer::sum);
            }
        }
        List<ProjectTidyScope.Member> members = new ArrayList<>();
        List<ProjectTidyScope.Excluded> excluded = new ArrayList<>(scope.excluded());
        for (ProjectTidyScope.Member m : scope.reviewed()) {
            if (review.unlistedMaterialIds().contains(m.materialId())) {
                excluded.add(new ProjectTidyScope.Excluded(m.materialId(), m.filename(),
                        TidyExcludeReason.OVER_BUDGET.name(), TidyExcludeReason.OVER_BUDGET.label()));
                continue;
            }
            members.add(new ProjectTidyScope.Member(m.materialId(), m.filename(), m.fileHash(),
                    m.analysisVersion(), m.sectionCount(), detailed.getOrDefault(m.materialId(), 0),
                    listed.getOrDefault(m.materialId(), 0)));
        }
        ProjectTidyScope finalScope = new ProjectTidyScope(scope.courseId(), scope.treeVersion(),
                scope.topicCount(), scope.treeLinesShown(), members, excluded,
                review.partial() || review.listedSectionIds().size() < input.sections().size(),
                input.sections().size(), review.detailSectionIds().size(), review.listedSectionIds().size(),
                review.modelCalls());
        return new Input(input.course(), input.topics(), input.topicLinks(), input.sections(),
                input.materialsById(), input.assignments(), finalScope);
    }

    private static Integer first(List<MaterialSection> sections, Long materialId) {
        return sections.stream().filter(s -> materialId.equals(s.getMaterialId()))
                .map(MaterialSection::getAnalysisVersion).findFirst().orElse(null);
    }

    /**
     * 요청 시점에 고정한 자료만 남긴다. 그 목록에 없던 자료는 "이번 정리 대상이 아님"이므로
     * 제외 사유로도 적지 않는다 — 사용자가 요청한 뒤에 생긴 것이고, 화면이 따로
     * "새 자료 N개를 반영할 수 있어요"로 알린다.
     *
     * <p>반대로 고정 목록에 있었는데 지금 쓸 수 없게 된 자료는 사유와 함께 제외로 남는다.
     */
    private Split restrictTo(Split split, Set<Long> allowed) {
        List<CourseMaterial> ready = split.ready().stream()
                .filter(m -> allowed.contains(m.getMaterialId())).toList();
        List<ProjectTidyScope.Excluded> excluded = split.excluded().stream()
                .filter(e -> allowed.contains(e.materialId())).collect(java.util.stream.Collectors.toList());
        Set<Long> present = new HashSet<>();
        ready.forEach(m -> present.add(m.getMaterialId()));
        excluded.forEach(e -> present.add(e.materialId()));
        for (Long materialId : allowed) {
            if (!present.contains(materialId)) {
                excluded.add(new ProjectTidyScope.Excluded(materialId, null,
                        TidyExcludeReason.UNLINKED.name(), TidyExcludeReason.UNLINKED.label()));
            }
        }
        return new Split(ready, excluded, split.sectionCounts());
    }

    /** 이 프로젝트에 연결된 자료를 "쓸 수 있는 것"과 "뺀 것"으로 가른다. */
    private record Split(List<CourseMaterial> ready, List<ProjectTidyScope.Excluded> excluded,
                         Map<Long, Integer> sectionCounts) {
    }

    private Split split(Long userId, Long courseId) {
        List<MaterialLink> links = materialLinkMapper.findByCourseIdAndUserId(courseId, userId);
        List<Long> materialIds = links.stream().map(MaterialLink::getMaterialId).distinct().toList();
        if (materialIds.isEmpty()) {
            return new Split(List.of(), List.of(), Map.of());
        }
        List<CourseMaterial> materials = courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(materialIds, userId)
                .stream().filter(m -> m.getStatus() == MaterialStatus.ACTIVE).toList();
        Map<Long, MaterialAnalysisStatusResponse> statuses = new HashMap<>();
        for (MaterialAnalysisStatusResponse status : statusService.statuses(userId, materials)) {
            statuses.put(status.getMaterialId(), status);
        }
        Map<Long, Integer> sectionCounts = new HashMap<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(materialIds, userId)) {
            sectionCounts.merge(section.getMaterialId(), 1, Integer::sum);
        }
        List<CourseMaterial> ready = new ArrayList<>();
        List<ProjectTidyScope.Excluded> excluded = new ArrayList<>();
        for (CourseMaterial material : materials) {
            MaterialAnalysisStatusResponse status = statuses.get(material.getMaterialId());
            String state = status == null ? "NONE" : status.getState();
            int sections = sectionCounts.getOrDefault(material.getMaterialId(), 0);
            TidyExcludeReason reason = switch (state) {
                case "DONE", "PARTIAL" -> sections > 0 ? null : TidyExcludeReason.NO_TEXT;
                case "NO_TEXT" -> TidyExcludeReason.NO_TEXT;
                case "FAILED", "UNAVAILABLE", "CANCELLED" -> TidyExcludeReason.FAILED;
                default -> TidyExcludeReason.ANALYZING;
            };
            if (reason == null) {
                ready.add(material);
            } else {
                excluded.add(new ProjectTidyScope.Excluded(material.getMaterialId(),
                        material.getOriginalFilename(), reason.name(), reason.label()));
            }
        }
        ready.sort(Comparator.comparing(CourseMaterial::getMaterialId));
        return new Split(ready, excluded, sectionCounts);
    }

    /**
     * 예산 안에서 구간을 고른다. 자르더라도 <b>어느 자료에서 얼마나</b> 잘랐는지 셀 수 있게
     * 원래 순서를 기억한 채 우선순위로 뽑는다.
     */
    List<MaterialSection> chooseWithinBudget(List<MaterialSection> all, Set<Long> alreadyLinkedMaterials) {
        List<MaterialSection> sorted = new ArrayList<>(all);
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            order.put(all.get(i).getSectionId(), i);
        }
        sorted.sort(Comparator
                .comparingInt((MaterialSection s) -> alreadyLinkedMaterials.contains(s.getMaterialId()) ? 1 : 0)
                .thenComparingInt(s -> s.isAssignmentCue() ? 0 : 1)
                .thenComparingInt(s -> order.getOrDefault(s.getSectionId(), 0)));
        List<MaterialSection> chosen = new ArrayList<>();
        int used = 0;
        for (MaterialSection section : sorted) {
            int cost = costOf(section);
            if (!chosen.isEmpty() && used + cost > sectionCharBudget) {
                continue;
            }
            chosen.add(section);
            used += cost;
        }
        chosen.sort(Comparator.comparingInt(s -> order.getOrDefault(s.getSectionId(), 0)));
        return chosen;
    }

    private static int costOf(MaterialSection section) {
        int title = section.getDisplayTitle() == null ? 0 : section.getDisplayTitle().length();
        int task = section.getTaskText() == null ? 0 : Math.min(MAX_TASK_CHARS, section.getTaskText().length());
        return 40 + title + task;
    }
}
