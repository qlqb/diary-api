package com.jungwoo.project.memo.learning.structure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicChangeProposalMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposalStatus;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 자료 정리 변경안의 생성(분석기가 부른다)·조회·적용·폐기.
 *
 * <p>적용은 변경안 행을 FOR UPDATE로 잡고 상태를 확인한 뒤 {@link TopicTreeEditor}에 넘긴다. 트리 버전이
 * 맞지 않으면 CONFLICT로 남기고 409 — 같은 변경안의 재적용·동시 적용·오래된 diff 적용은 전부 여기서 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicChangeProposalService {

    private static final TypeReference<List<TopicChangeOp>> OPS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final TopicChangeProposalMapper proposalMapper;
    private final CourseTopicMapper topicMapper;
    private final CourseMapper courseMapper;
    private final CourseService courseService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialSectionMapper sectionMapper;
    private final TopicTreeEditor treeEditor;
    private final ObjectMapper objectMapper;

    // ===== 생성(분석기) =====

    /**
     * 검증된 작업 목록으로 변경안을 저장한다. 같은 (자료, 프로젝트)에 열린 변경안이 있으면 STALE로 내리고
     * 새 것을 만든다 — 새 분석이 더 최신 원문을 봤기 때문이다. 사용자 편집은 적용 시점에만 있고
     * (선택·제목 수정), 저장된 열린 변경안에 사용자 편집이 실려 있지 않으므로 덮는 것이 없다.
     */
    @Transactional
    public TopicChangeProposal create(Long userId, Long courseId, Long materialId, Long jobId, String fileHash,
                                      Long baseTreeVersion, TopicChangeOpsValidator.Result validated, String model) {
        TopicChangeProposal open = proposalMapper.findOpenByMaterialAndCourse(materialId, courseId, userId);
        if (open != null) {
            proposalMapper.updateStatus(open.getProposalId(), userId, TopicChangeProposalStatus.STALE.name(),
                    null, LocalDateTime.now());
        }
        TopicChangeProposalStatus status = validated.ops().isEmpty()
                ? TopicChangeProposalStatus.EMPTY : TopicChangeProposalStatus.PROPOSED;
        TopicChangeProposal proposal = TopicChangeProposal.builder()
                .userId(userId).courseId(courseId).materialId(materialId).jobId(jobId).fileHash(fileHash)
                .baseTreeVersion(baseTreeVersion).status(status)
                .summaryJson(writeJson(validated.summary()))
                .opsJson(writeJson(validated.ops()))
                .model(model)
                .build();
        try {
            proposalMapper.insert(proposal);
        } catch (DuplicateKeyException e) {
            // 같은 (자료, 프로젝트)의 LINK 작업이 동시에 둘 돌았다. 먼저 저장된 쪽을 쓴다.
            TopicChangeProposal winner = proposalMapper.findOpenByMaterialAndCourse(materialId, courseId, userId);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
        if (status == TopicChangeProposalStatus.EMPTY) {
            proposalMapper.updateStatus(proposal.getProposalId(), userId, status.name(), null, LocalDateTime.now());
        }
        return proposal;
    }

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public List<TopicChangeProposalResponse> listByCourse(Long userId, Long courseId, boolean includeResolved) {
        Course course = courseService.getOwned(userId, courseId);
        List<TopicChangeProposal> proposals = includeResolved
                ? proposalMapper.findByCourseId(courseId, userId, 20)
                : proposalMapper.findOpenByCourseId(courseId, userId);
        if (proposals.isEmpty()) {
            return List.of();
        }
        Map<Long, String> titles = topicMapper.findByCourseIdAndUserIdIncludingArchived(courseId, userId).stream()
                .collect(Collectors.toMap(CourseTopic::getTopicId, CourseTopic::getTitle, (a, b) -> a));
        List<Long> materialIds = proposals.stream().map(TopicChangeProposal::getMaterialId).distinct().toList();
        Map<Long, CourseMaterial> materials = courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(materialIds, userId)
                .stream().collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
        List<TopicChangeProposalResponse> out = new ArrayList<>();
        for (TopicChangeProposal proposal : proposals) {
            out.add(toResponse(proposal, course, titles, materials.get(proposal.getMaterialId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public TopicChangeProposalResponse get(Long userId, Long proposalId) {
        TopicChangeProposal proposal = getOwned(userId, proposalId);
        Course course = courseService.getOwned(userId, proposal.getCourseId());
        Map<Long, String> titles = topicMapper.findByCourseIdAndUserIdIncludingArchived(proposal.getCourseId(), userId)
                .stream().collect(Collectors.toMap(CourseTopic::getTopicId, CourseTopic::getTitle, (a, b) -> a));
        CourseMaterial material = courseMaterialMapper
                .findByIdsAndUserIdIncludingDeleted(List.of(proposal.getMaterialId()), userId).stream()
                .findFirst().orElse(null);
        return toResponse(proposal, course, titles, material);
    }

    // ===== 적용 · 폐기 =====

    @Transactional
    public TopicChangeProposalResponse apply(Long userId, Long proposalId, TopicChangeApplyRequest request) {
        TopicChangeProposal proposal = proposalMapper.findByIdAndUserIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.TOPIC_CHANGE_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != TopicChangeProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.TOPIC_CHANGE_PROPOSAL_RESOLVED);
        }
        // 대상이 아직 유효한가: 자료 ACTIVE·프로젝트 ACTIVE·연결 존재. 아니면 STALE.
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(proposal.getMaterialId(), userId);
        Course course = courseMapper.findByIdAndUserId(proposal.getCourseId(), userId);
        boolean linked = materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(
                proposal.getMaterialId(), proposal.getCourseId(), userId) != null;
        if (material == null || course == null || course.getStatus() == CourseStatus.ARCHIVED || !linked
                || !proposal.getFileHash().equals(material.getFileHash())) {
            proposalMapper.updateStatus(proposalId, userId, TopicChangeProposalStatus.STALE.name(), null,
                    LocalDateTime.now());
            throw new ConflictException(ErrorCode.TOPIC_CHANGE_PROPOSAL_RESOLVED,
                    "자료·연결·프로젝트가 바뀌어 이 변경안은 더 이상 적용할 수 없어요");
        }

        List<TopicChangeOp> all = readOps(proposal.getOpsJson());
        List<TopicChangeOp> selected = select(all, request);
        Map<Long, MaterialSection> sections = sectionMapper
                .findActiveByMaterialIdAndHash(material.getMaterialId(), material.getFileHash()).stream()
                .collect(Collectors.toMap(MaterialSection::getSectionId, s -> s, (a, b) -> a));

        TopicTreeEditor.Applied applied;
        try {
            applied = treeEditor.apply(userId, proposal.getCourseId(), proposal.getMaterialId(), selected,
                    proposal.getBaseTreeVersion(), sections, TopicLinkOrigin.PROPOSAL_APPLIED);
        } catch (ConflictException e) {
            // 새 트랜잭션이 아니므로 여기서의 상태 갱신은 예외와 함께 롤백된다. 별도 빈 없이 처리하려면
            // 호출자가 다시 읽었을 때 stale=true로 보이므로(버전 비교) 상태 갱신 없이도 화면은 안다.
            throw e;
        }
        proposalMapper.updateStatus(proposalId, userId, TopicChangeProposalStatus.APPLIED.name(),
                writeJson(Map.of("selected", selected, "created", applied.createdTopicIds(),
                        "reviewNotes", applied.reviewNotes())), LocalDateTime.now());
        log.info("변경안 적용: proposalId={}, courseId={}, link={}, add={}, rename={}, move={}, merge={}, split={}",
                proposalId, proposal.getCourseId(), applied.linked(), applied.added(), applied.renamed(),
                applied.moved(), applied.merged(), applied.split());
        return get(userId, proposalId);
    }

    @Transactional
    public TopicChangeProposalResponse dismiss(Long userId, Long proposalId) {
        TopicChangeProposal proposal = proposalMapper.findByIdAndUserIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.TOPIC_CHANGE_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != TopicChangeProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.TOPIC_CHANGE_PROPOSAL_RESOLVED);
        }
        proposalMapper.updateStatus(proposalId, userId, TopicChangeProposalStatus.DISMISSED.name(), null,
                LocalDateTime.now());
        return get(userId, proposalId);
    }

    /** 자료 삭제·연결 해제·프로젝트 보관에서 부른다. courseId가 null이면 그 자료의 모든 프로젝트. */
    public void staleFor(Long userId, Long materialId, Long courseId) {
        proposalMapper.staleOpen(materialId, courseId, userId, LocalDateTime.now());
    }

    // ===== 내부 =====

    private List<TopicChangeOp> select(List<TopicChangeOp> all, TopicChangeApplyRequest request) {
        List<Integer> indexes = request == null ? null : request.getSelectedOpIndexes();
        Map<Integer, String> overrides = request == null || request.getTitleOverrides() == null
                ? Map.of() : request.getTitleOverrides();
        Set<Integer> chosen = indexes == null ? null : new HashSet<>(indexes);
        List<TopicChangeOp> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (chosen != null && !chosen.contains(i)) {
                continue;
            }
            TopicChangeOp op = all.get(i);
            String override = overrides.get(i);
            if (override != null && !override.isBlank()
                    && (TopicChangeOp.ADD.equals(op.op()) || TopicChangeOp.RENAME.equals(op.op()))) {
                op = op.withTitle(override.trim());
            }
            out.add(op);
        }
        return out;
    }

    private TopicChangeProposalResponse toResponse(TopicChangeProposal proposal, Course course,
                                                   Map<Long, String> titles, CourseMaterial material) {
        List<TopicChangeOp> ops = readOps(proposal.getOpsJson());
        Set<Long> sectionIds = new HashSet<>();
        collectSectionIds(ops, sectionIds);
        List<TopicChangeProposalResponse.SectionBrief> briefs = new ArrayList<>();
        if (!sectionIds.isEmpty()) {
            for (MaterialSection section : sectionMapper.findByIdsAndUserId(new ArrayList<>(sectionIds), proposal.getUserId())) {
                briefs.add(new TopicChangeProposalResponse.SectionBrief(section.getSectionId(),
                        section.getDisplayTitle(), section.locator(), readStrings(section.getRolesJson()),
                        section.getTaskText(), section.getExcerpt()));
            }
        }
        Map<Long, String> used = new HashMap<>();
        collectTopicIds(ops, used, titles);
        long current = course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion();
        return TopicChangeProposalResponse.builder()
                .proposalId(proposal.getProposalId())
                .courseId(proposal.getCourseId())
                .materialId(proposal.getMaterialId())
                .materialFilename(material == null ? null : material.getOriginalFilename())
                .status(proposal.getStatus())
                .baseTreeVersion(proposal.getBaseTreeVersion())
                .currentTreeVersion(current)
                .stale(proposal.getStatus() == TopicChangeProposalStatus.PROPOSED
                        && proposal.getBaseTreeVersion() != null && proposal.getBaseTreeVersion() != current)
                .summary(readSummary(proposal.getSummaryJson()))
                .ops(ops)
                .topicTitles(used)
                .sections(briefs)
                .createdAt(proposal.getCreatedAt())
                .resolvedAt(proposal.getResolvedAt())
                .build();
    }

    private static void collectSectionIds(List<TopicChangeOp> ops, Set<Long> out) {
        for (TopicChangeOp op : ops) {
            if (op.sectionIds() != null) {
                out.addAll(op.sectionIds());
            }
            if (op.children() != null) {
                collectSectionIds(op.children(), out);
            }
        }
    }

    private static void collectTopicIds(List<TopicChangeOp> ops, Map<Long, String> out, Map<Long, String> titles) {
        for (TopicChangeOp op : ops) {
            for (Long id : new Long[]{op.topicId(), op.parentTopicId(), op.survivingTopicId()}) {
                if (id != null && titles.containsKey(id)) {
                    out.put(id, titles.get(id));
                }
            }
            if (op.absorbedTopicIds() != null) {
                for (Long id : op.absorbedTopicIds()) {
                    if (titles.containsKey(id)) {
                        out.put(id, titles.get(id));
                    }
                }
            }
            if (op.children() != null) {
                collectTopicIds(op.children(), out, titles);
            }
        }
    }

    private TopicChangeProposal getOwned(Long userId, Long proposalId) {
        TopicChangeProposal proposal = proposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.TOPIC_CHANGE_PROPOSAL_NOT_FOUND);
        }
        return proposal;
    }

    private List<TopicChangeOp> readOps(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, OPS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStrings(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private TopicChangeOpsValidator.Summary readSummary(String json) {
        try {
            return objectMapper.readValue(json, TopicChangeOpsValidator.Summary.class);
        } catch (Exception e) {
            return new TopicChangeOpsValidator.Summary(0, 0, 0, 0, 0, 0, false, 0);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }
}
