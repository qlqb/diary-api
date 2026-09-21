package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicChangeProposalMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangeOpsValidator;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import com.jungwoo.project.memo.learning.structure.TopicTreeEditor;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyEdits;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.learning.tidy.domain.TidyJobStatus;
import com.jungwoo.project.memo.learning.tidy.domain.TidyProposalStatus;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트 단위 자료 정리의 바깥쪽: 요청·조회·검토 편집·적용·폐기.
 *
 * <p>이 화면의 계약 넷:
 * <ol>
 *   <li><b>정리는 사용자가 누를 때만 시작된다.</b> 자료 분석이 끝나도 정리안이 생기지 않는다.</li>
 *   <li><b>정리안은 고정이다.</b> 만든 뒤 새 자료가 끝나도 섞이지 않고, "반영할 수 있어요"로만 알린다.</li>
 *   <li><b>검토 편집은 트리 변경이 아니다.</b> 저장돼도 학습 구조는 그대로다.</li>
 *   <li><b>적용은 사용자가 본 그대로일 때만 된다.</b> 정리안 판·편집 판·트리 판·근거 자료가 전부
 *       맞아야 한다. 하나라도 어긋나면 편집을 보존한 채 거절하고 이유를 말한다.
 *       동시 적용은 정리안 행의 {@code FOR UPDATE}가 직렬화한다 — 트리는 한 번만 바뀐다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTidyService {

    private static final TypeReference<List<TopicChangeOp>> OPS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, EditValue>> EDITS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final int HISTORY_LIMIT = 20;

    private final ProjectTidyMapper tidyMapper;
    private final ProjectTidyInputBuilder inputBuilder;
    private final CourseService courseService;
    private final CourseTopicMapper topicMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialSectionMapper sectionMapper;
    private final TopicChangeProposalMapper legacyProposalMapper;
    private final TopicTreeEditor treeEditor;
    private final ObjectMapper objectMapper;

    /** edits_json의 값. */
    /**
     * 검토 중 고친 것 하나.
     *
     * @param needsConfirm 판이 바뀌면서 옮겨 왔는데 대응이 확실하지 않다. 사용자가 고르기 전에는
     *                     적용하지 않는다 — 확인하지 않은 판단으로 트리를 바꾸지 않기 위해서다
     * @param carriedFrom  어느 제안에 붙어 있던 편집인지. 화면이 "전에는 이랬고 지금은 이렇다"를
     *                     보여주려면 옛 제안의 말이 남아 있어야 한다
     */
    record EditValue(boolean excluded, String title, boolean needsConfirm, CarriedFrom carriedFrom) {

        EditValue(boolean excluded, String title, boolean needsConfirm) {
            this(excluded, title, needsConfirm, null);
        }

        EditValue withConfirmed() {
            return new EditValue(excluded, title, false, carriedFrom);
        }
    }

    /** 승계 전 판에서 이 편집이 붙어 있던 제안. */
    record CarriedFrom(String changeId, String text, String reason) {
    }

    /**
     * 요청 시점에 고정한 입력. input_snapshot_json으로 저장된다.
     *
     * <p>왜 id만 두는가: 내용(구간·트리)까지 복사해 두면 실행 시점에 그 복사본이 진짜와 다를 수
     * 있고, 어느 쪽이 맞는지 정할 근거가 없다. "무엇을 대상으로 삼기로 했는가"만 고정하고
     * 내용은 실행할 때 읽되, 그 사이 못 쓰게 된 것은 사유와 함께 제외로 남긴다.
     */
    record InputSnapshot(List<Long> materialIds, List<ProjectTidyScope.Excluded> excludedAtRequest) {
    }

    // ===== 요청 =====

    /**
     * 정리를 시작한다. 작업을 하나 만들고 바로 돌아온다 — 모델 호출은 폴러가 한다.
     *
     * <p>중복 클릭·두 탭은 DB의 부분 유일 인덱스가 막는다. 이미 도는 작업이 있으면 그 작업을
     * 그대로 돌려준다(409가 아니다 — 사용자가 원한 것은 "정리 시작"이고 이미 그렇게 돼 있다).
     *
     * @param refresh true면 검토 중인 정리안을 새 자료까지 넣어 다시 만든다. 새 것이 성공할
     *                때까지 기존 정리안과 편집은 그대로 남는다
     */
    @Transactional
    public ProjectTidyResponse request(Long userId, Long courseId, boolean refresh) {
        Course course = courseService.getOwned(userId, courseId);
        ProjectTidyJob running = tidyMapper.findOpenJobByCourse(courseId, userId);
        if (running != null) {
            return view(userId, courseId);
        }
        ProjectTidyProposal open = tidyMapper.findOpenProposalByCourse(courseId, userId);
        if (open != null && !refresh) {
            // 이미 검토할 것이 있다. 같은 입력으로 다시 만들지 않는다.
            return view(userId, courseId);
        }
        ProjectTidyInputBuilder.Preview preview = inputBuilder.preview(userId, courseId);
        if (!preview.canTidy()) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_NO_MATERIALS,
                    preview.analyzingCount() > 0
                            ? "아직 분석 중인 자료 " + preview.analyzingCount() + "개뿐이에요. 끝나면 정리할 수 있어요"
                            : "이 프로젝트에 분석이 끝난 자료가 없어요");
        }
        Long maxGeneration = tidyMapper.findMaxGeneration(courseId);
        ProjectTidyJob job = ProjectTidyJob.builder()
                .userId(userId).courseId(courseId)
                .generation(maxGeneration == null ? 1 : maxGeneration + 1)
                .status(TidyJobStatus.QUEUED)
                .baseTreeVersion(course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion())
                .previousProposalId(open == null ? null : open.getProposalId())
                .maxAttempts(3)
                .nextRunAt(LocalDateTime.now())
                // ★ 입력을 지금 고정한다. 모델이 도는 동안 분석이 더 끝나도 이번 정리에는 들어가지
                //   않는다 — 사용자가 승인 화면에서 볼 근거와 실제로 쓴 근거를 같게 하기 위해서다.
                .inputSnapshotJson(writeJson(new InputSnapshot(
                        preview.ready().stream().map(ProjectTidyInputBuilder.Preview.Ready::materialId).toList(),
                        preview.excluded())))
                .build();
        try {
            tidyMapper.insertJob(job);
        } catch (DuplicateKeyException e) {
            // 같은 순간에 다른 요청이 먼저 만들었다. 그 작업을 쓴다.
            log.info("정리 요청이 겹쳤다 — 먼저 만들어진 작업을 쓴다: courseId={}", courseId);
            return view(userId, courseId);
        }
        log.info("프로젝트 정리 요청: courseId={}, jobId={}, 세대={}, 대상 자료={}, 제외={}",
                courseId, job.getJobId(), job.getGeneration(), preview.readyCount(), preview.excluded().size());
        return view(userId, courseId);
    }

    // ===== 조회 =====

    /** 화면 하나가 필요한 전부. 정리안이 없어도 "지금 정리할 수 있는가"를 담아 돌려준다. */
    @Transactional(readOnly = true)
    public ProjectTidyResponse view(Long userId, Long courseId) {
        Course course = courseService.getOwned(userId, courseId);
        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, userId);
        ProjectTidyJob job = tidyMapper.findOpenJobByCourse(courseId, userId);
        if (job == null) {
            ProjectTidyJob latest = tidyMapper.findLatestJobByCourse(courseId, userId);
            // 끝난 작업은 실패했을 때만 보여준다. 성공은 정리안 자체가 말한다.
            if (latest != null && (latest.getStatus() == TidyJobStatus.FAILED
                    || latest.getStatus() == TidyJobStatus.UNAVAILABLE)) {
                job = latest;
            }
        }
        return toResponse(userId, course, proposal, job);
    }

    /** 이 프로젝트의 지난 정리안(적용·폐기·물러난 것 포함). */
    @Transactional(readOnly = true)
    public List<ProjectTidyResponse> history(Long userId, Long courseId) {
        Course course = courseService.getOwned(userId, courseId);
        List<ProjectTidyResponse> out = new ArrayList<>();
        for (ProjectTidyProposal proposal : tidyMapper.findHistoryByCourse(courseId, userId, HISTORY_LIMIT)) {
            if (proposal.getStatus() == TidyProposalStatus.PROPOSED) {
                continue;
            }
            out.add(toResponse(userId, course, proposal, null));
        }
        return out;
    }

    // ===== 검토 편집 =====

    /**
     * 고친 제목과 빼 둔 선택을 저장한다. 자동 저장이 여기로 들어온다.
     *
     * <p>트리는 바뀌지 않는다. 판 번호가 어긋나면 저장하지 않고 409 — 다른 탭이 먼저 저장한 것을
     * 덮지 않는다. 화면은 실패를 삼키지 말고 사용자에게 보여야 한다.
     */
    @Transactional
    public ProjectTidyResponse saveEdits(Long userId, Long proposalId, ProjectTidyRequests.SaveEdits request) {
        ProjectTidyProposal proposal = tidyMapper.findProposalByIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.PROJECT_TIDY_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != TidyProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_PROPOSAL_RESOLVED);
        }
        Set<String> known = changeIds(proposal);

        /*
         * 지금 저장돼 있는 편집을 먼저 읽는다. needsConfirm과 carriedFrom은 <서버가 정한 것>이라
         * 요청에 실려 오지 않는다. 예전에는 여기서 모두 false로 덮어써서, 사용자가 아무 제목이나
         * 한 글자 고치기만 해도 "확인 필요"가 소리 없이 사라졌다 — 확인하지 않은 승계 편집이
         * 확인된 것처럼 되어 그대로 적용될 수 있었다.
         */
        Map<String, EditValue> current = readEdits(tidyMapper.findEdits(proposalId, userId));
        Map<String, String> resolutions = normalizeResolutions(request, proposal);

        Map<String, EditValue> edits = new LinkedHashMap<>();
        if (request.getEdits() != null) {
            request.getEdits().forEach((changeId, value) -> {
                // 모르는 changeId는 버린다. 옛 판의 편집이 새 판에 그대로 들어오면 엉뚱한 것을 가린다.
                if (!known.contains(changeId) || value == null) {
                    return;
                }
                EditValue before = current.get(changeId);
                String resolution = resolutions.get(changeId);
                if ("DROP".equals(resolution)) {
                    // 승계된 편집을 버린다 = 이 변경에 대한 내 판단을 없앤다. 새 제안 그대로 간다.
                    return;
                }
                boolean needsConfirm = before != null && before.needsConfirm() && !"KEEP".equals(resolution);
                CarriedFrom carriedFrom = needsConfirm && before != null ? before.carriedFrom() : null;
                edits.put(changeId, new EditValue(value.isExcluded(), trimToNull(value.getTitle()),
                        needsConfirm, carriedFrom));
            });
        }
        String json = writeJson(edits);
        long expected = request.getEditRevision() == null ? 0 : request.getEditRevision();
        ProjectTidyEdits existing = tidyMapper.findEditsForUpdate(proposalId, userId);
        if (existing == null) {
            if (expected > 1) {
                throw new ConflictException(ErrorCode.PROJECT_TIDY_REVISION_STALE);
            }
            tidyMapper.insertEditsIgnore(ProjectTidyEdits.builder()
                    .proposalId(proposalId).userId(userId).editsJson(json).build());
            existing = tidyMapper.findEditsForUpdate(proposalId, userId);
            if (existing != null && !json.equals(existing.getEditsJson())) {
                // 같은 순간에 다른 요청이 먼저 만들었다. 판을 맞춰 다시 시도하게 한다.
                throw new ConflictException(ErrorCode.PROJECT_TIDY_REVISION_STALE);
            }
        } else if (tidyMapper.updateEdits(proposalId, userId, expected, json) != 1) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_REVISION_STALE);
        }
        Course course = courseService.getOwned(userId, proposal.getCourseId());
        return toResponse(userId, course, tidyMapper.findProposalById(proposalId, userId), null);
    }

    /** 어떤 변경이 확인을 기다리는지 이름으로 말한다. details가 그대로 화면에 나간다. */
    private String describeUnconfirmed(List<TopicChangeOp> ops, List<String> changeIds) {
        Map<String, TopicChangeOp> byId = new LinkedHashMap<>();
        ops.forEach(op -> byId.put(op.changeId(), op));
        List<String> names = new ArrayList<>();
        for (String changeId : changeIds) {
            TopicChangeOp op = byId.get(changeId);
            names.add(op == null ? changeId : TidyChangeText.shortName(op));
        }
        return "이전 판에서 옮겨 온 편집 " + names.size() + "건을 아직 확인하지 않았어요: "
                + String.join(", ", names);
    }

    /**
     * 확인 처리를 받아들일지 정한다. 클라이언트의 말만 믿지 않는다.
     *
     * <p>세 가지를 확인한다. (1) 보낸 판이 지금 정리안의 판과 같은가 — 사용자가 확인한 것은
     * 그때 본 제안이다. (2) 값이 KEEP/DROP인가. (3) 실제로 확인이 필요한 항목인가 — 아닌 것에
     * 대한 "확인"은 아무 뜻이 없으므로 무시한다.
     */
    private Map<String, String> normalizeResolutions(ProjectTidyRequests.SaveEdits request,
                                                     ProjectTidyProposal proposal) {
        if (request.getResolveCarried() == null || request.getResolveCarried().isEmpty()) {
            return Map.of();
        }
        if (request.getRevision() == null || !request.getRevision().equals(proposal.getRevision())) {
            log.info("확인 처리를 받지 않는다 — 판이 다르다: proposalId={}, 보낸 판={}, 지금 판={}",
                    proposal.getProposalId(), request.getRevision(), proposal.getRevision());
            return Map.of();
        }
        Map<String, EditValue> stored = readEdits(tidyMapper.findEdits(proposal.getProposalId(),
                proposal.getUserId()));
        Map<String, String> out = new LinkedHashMap<>();
        request.getResolveCarried().forEach((changeId, decision) -> {
            EditValue value = stored.get(changeId);
            if (value == null || !value.needsConfirm()) {
                return;
            }
            if ("KEEP".equals(decision) || "DROP".equals(decision)) {
                out.put(changeId, decision);
            }
        });
        return out;
    }

    // ===== 적용 =====

    /**
     * 고른 변경을 학습 구조에 반영한다. 한 트랜잭션, 전부 아니면 전무.
     *
     * <p>동시 적용을 막는 것은 <b>맨 앞의 {@code FOR UPDATE}</b>다. 두 탭이 같은 순간에 눌러도
     * 뒤엣것은 그 잠금에서 기다렸다가, 앞엣것이 커밋한 뒤 다시 읽어 APPLIED를 보고 멈춘다.
     * 트리는 한 번만 바뀐다.
     *
     * <p>맨 끝의 조건부 전이({@code resolveProposalIfOpen})는 그 위의 둘째 자물쇠다 — 잠금이
     * 어떤 이유로든 먼저 풀렸다면 0행이 되고, 그러면 이 트랜잭션 전체가 롤백된다. 트리 수정만
     * 남고 상태가 뒤처지는 상태는 만들어지지 않는다.
     */
    @Transactional
    public ProjectTidyResponse apply(Long userId, Long proposalId, ProjectTidyRequests.Apply request) {
        ProjectTidyProposal proposal = tidyMapper.findProposalByIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.PROJECT_TIDY_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != TidyProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_PROPOSAL_RESOLVED);
        }
        if (!proposal.getRevision().equals(request.getRevision())) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_REVISION_STALE);
        }
        ProjectTidyEdits edits = tidyMapper.findEdits(proposalId, userId);
        long currentEditRevision = edits == null ? 0 : edits.getEditRevision();
        if (currentEditRevision != (request.getEditRevision() == null ? 0 : request.getEditRevision())) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_REVISION_STALE,
                    "다른 곳에서 검토 내용을 먼저 고쳤어요. 최신 내용을 다시 불러옵니다");
        }

        Course course = courseService.getOwned(userId, proposal.getCourseId());
        long treeVersion = course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion();
        if (treeVersion != proposal.getBaseTreeVersion() || treeVersion != request.getBaseTreeVersion()) {
            throw new ConflictException(ErrorCode.TOPIC_TREE_CONFLICT,
                    "이 정리안을 만든 뒤 학습 구조가 바뀌었어요. 다시 정리해야 해요");
        }

        List<TopicChangeOp> all = readOps(proposal.getOpsJson());
        Map<String, List<String>> dependsOn = TopicChangePlan.dependencies(all);
        Set<String> selected = new LinkedHashSet<>(
                request.getSelectedChangeIds() == null ? List.of() : request.getSelectedChangeIds());
        Set<String> known = new HashSet<>();
        all.forEach(op -> known.add(op.changeId()));
        selected.retainAll(known);
        Map<String, List<String>> missing = TopicChangePlan.missingDependencies(selected, dependsOn);
        if (!missing.isEmpty()) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_SELECTION_INCOMPLETE, describeMissing(all, missing));
        }

        /*
         * 확인하지 않은 승계 편집이 남아 있으면 적용하지 않는다.
         *
         * 고른 것만 보지 않고 <전부>를 보는 이유: 승계된 "제외"도 편집이다. 확인하지 않은
         * 제외가 남아 있으면 새 판의 어떤 작업이 사용자가 보지도 않은 옛 판단 때문에 조용히
         * 빠진 채 적용된다. 빠진 것은 화면에 나타나지 않으므로 사용자가 알아챌 방법이 없다.
         */
        Map<String, EditValue> saved = readEdits(edits);
        List<String> unconfirmed = saved.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().needsConfirm())
                .map(Map.Entry::getKey)
                .toList();
        if (!unconfirmed.isEmpty()) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_CARRIED_EDIT_UNCONFIRMED,
                    describeUnconfirmed(readOps(proposal.getOpsJson()), unconfirmed));
        }

        // 근거가 아직 그대로인가. 잠근 채 확인해 검증과 쓰기 사이에 바뀌지 않게 한다.
        Map<Long, MaterialSection> sections = verifyEvidence(userId, proposal);

        List<TopicChangeOp> chosen = new ArrayList<>();
        for (TopicChangeOp op : all) {
            if (!selected.contains(op.changeId())) {
                continue;
            }
            String title = titleOverride(op, saved, request.getTitleOverrides());
            chosen.add(title == null ? op : op.withTitle(title));
        }

        // 고른 것이 없으면 트리 판을 올리지 않는다. 아무것도 바뀌지 않았는데 다른 정리안이
        // 어긋나게 만들 이유가 없다.
        TopicTreeEditor.Applied applied = null;
        if (!chosen.isEmpty()) {
            applied = treeEditor.apply(userId, proposal.getCourseId(), null, chosen,
                    proposal.getBaseTreeVersion(), sections, TopicLinkOrigin.PROPOSAL_APPLIED);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedChangeIds", new ArrayList<>(selected));
        result.put("appliedCount", chosen.size());
        if (applied != null) {
            result.put("created", applied.createdTopicIds());
            result.put("reviewNotes", applied.reviewNotes());
            result.put("counts", Map.of("link", applied.linked(), "add", applied.added(),
                    "rename", applied.renamed(), "move", applied.moved(),
                    "merge", applied.merged(), "split", applied.split()));
        }
        // 둘째 자물쇠. 0행이면 그 사이 누가 끝낸 것이고, 예외로 이 트랜잭션(트리 수정 포함)을 되돌린다.
        if (tidyMapper.resolveProposalIfOpen(proposalId, userId, proposal.getRevision(),
                TidyProposalStatus.APPLIED.name(), writeJson(result), LocalDateTime.now()) != 1) {
            throw new ConflictException(ErrorCode.PROJECT_TIDY_PROPOSAL_RESOLVED);
        }
        log.info("프로젝트 정리 적용: courseId={}, proposalId={}, 고른 변경={}/{}",
                proposal.getCourseId(), proposalId, chosen.size(), all.size());
        return toResponse(userId, courseService.getOwned(userId, proposal.getCourseId()),
                tidyMapper.findProposalById(proposalId, userId), null);
    }

    /**
     * 근거 자료가 검토할 때와 같은가. 다르면 적용하지 않는다 — 사용자가 본 근거와 다른 것을
     * 적용하게 되기 때문이다.
     *
     * <p>{@code FOR UPDATE}로 잠그는 이유: 확인한 뒤 트리를 고치는 사이에 자료가 지워지거나
     * 연결이 끊기면 "확인은 통과했는데 결과는 거짓"이 된다. 잠가 두면 그쪽이 기다린다.
     *
     * @return 이 정리안이 인용한, 아직 살아 있는 구간들
     */
    private Map<Long, MaterialSection> verifyEvidence(Long userId, ProjectTidyProposal proposal) {
        List<ProjectTidyProposalMaterial> members = tidyMapper.findProposalMaterials(
                proposal.getProposalId(), userId).stream().filter(ProjectTidyProposalMaterial::isIncluded).toList();
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> materialIds = members.stream().map(ProjectTidyProposalMaterial::getMaterialId).toList();
        Map<Long, CourseMaterial> materials = new HashMap<>();
        for (CourseMaterial material : courseMaterialMapper.findByIdsAndUserIdForUpdate(materialIds, userId)) {
            materials.put(material.getMaterialId(), material);
        }
        Set<Long> linked = new HashSet<>();
        for (MaterialLink link : materialLinkMapper.findByCourseIdAndUserIdForUpdate(
                proposal.getCourseId(), userId)) {
            linked.add(link.getMaterialId());
        }
        for (ProjectTidyProposalMaterial member : members) {
            CourseMaterial material = materials.get(member.getMaterialId());
            String problem = null;
            if (material == null || material.getStatus() != MaterialStatus.ACTIVE) {
                problem = "지워졌어요";
            } else if (!linked.contains(member.getMaterialId())) {
                problem = "이 프로젝트에서 연결이 끊겼어요";
            } else if (member.getFileHash() != null && !member.getFileHash().equals(material.getFileHash())) {
                problem = "파일이 바뀌었어요";
            }
            if (problem != null) {
                String name = material == null ? "자료" : material.getOriginalFilename();
                throw new ConflictException(ErrorCode.PROJECT_TIDY_EVIDENCE_CHANGED,
                        "「" + name + "」이(가) " + problem + ". 다시 정리해 주세요");
            }
        }
        Map<Long, MaterialSection> sections = new HashMap<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(materialIds, userId)) {
            sections.put(section.getSectionId(), section);
        }
        // 인용한 구간이 사라졌으면(재분석으로 교체) 적용하지 않는다.
        Set<Long> cited = new HashSet<>();
        collectSectionIds(readOps(proposal.getOpsJson()), cited);
        for (Long sectionId : cited) {
            if (!sections.containsKey(sectionId)) {
                throw new ConflictException(ErrorCode.PROJECT_TIDY_EVIDENCE_CHANGED,
                        "근거로 삼은 자료 구간이 다시 분석되어 바뀌었어요. 다시 정리해 주세요");
            }
        }
        return sections;
    }

    // ===== 폐기 · 미루기 =====

    /**
     * 버린다. 되살아나지 않는다.
     *
     * <p>도는 작업이 있으면 함께 무효화한다(임대 토큰을 올린다) — 늦게 끝난 작업이 버린 자리에
     * 정리안을 다시 놓지 못한다. 같은 입력으로 자동 재생성하지도 않는다. 사용자가 다시 누르는
     * 것만 새 정리안을 만든다.
     *
     * <p><b>적용과 같은 전이 규칙을 쓴다.</b> 예전에는 여기서 잠그지 않고 읽은 뒤 조건 없는
     * UPDATE를 했다. 그래서 이 순서가 가능했다 — 폐기가 PROPOSED를 읽는다 → 적용이
     * 커밋한다(트리가 바뀐다) → 폐기가 APPLIED를 DISMISSED로 덮는다. 트리는 적용됐는데
     * 이력은 "버림"이 되어, 사용자는 적용한 적 없는 변경을 트리에서 보게 된다.
     * 이제는 {@code FOR UPDATE}로 잠근 뒤 상태 조건이 붙은 전이만 쓴다. 적용이 먼저면
     * 0행이 되고, 덮는 대신 "이미 처리됨"으로 돌려준다.
     *
     * <p>잠금 순서는 적용과 같다: <b>정리안 → 작업 → 자료</b>. 두 경로가 같은 순서로
     * 잡으므로 서로 기다리다 엉키지 않는다.
     */
    @Transactional
    public ProjectTidyResponse dismiss(Long userId, Long courseId) {
        Course course = courseService.getOwned(userId, courseId);
        boolean alreadyResolved = false;

        /*
         * 잠근 뒤에 한 번 더 확인하는 이유: 잠그기 전에 읽은 id가 그 사이 물러나고 새 정리안이
         * 들어섰을 수 있다. 그러면 옛 것만 버리고 새 것이 남는다. 열린 안이 잠근 것과 같아질
         * 때까지 다시 본다 — 한 프로젝트에 열린 안은 하나뿐이라 몇 번이면 끝난다.
         */
        ProjectTidyProposal locked = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            ProjectTidyProposal open = tidyMapper.findOpenProposalByCourse(courseId, userId);
            if (open == null) {
                break;
            }
            locked = tidyMapper.findProposalByIdForUpdate(open.getProposalId(), userId);
            if (locked != null && locked.getStatus() == TidyProposalStatus.PROPOSED) {
                break;
            }
            // 잠그고 보니 이미 끝나 있었다. 그 사이 새 안이 생겼는지 다시 본다.
            alreadyResolved = true;
            locked = null;
        }

        // 작업 무효화는 정리안을 잠근 뒤에 한다(잠금 순서: 정리안 → 작업).
        tidyMapper.cancelOpenJobs(courseId, userId, "사용자가 버렸다");

        if (locked != null) {
            int moved = tidyMapper.resolveProposalIfOpen(locked.getProposalId(), userId, locked.getRevision(),
                    TidyProposalStatus.DISMISSED.name(), null, LocalDateTime.now());
            if (moved == 1) {
                log.info("프로젝트 정리안 폐기: courseId={}, proposalId={}", courseId, locked.getProposalId());
            } else {
                // 잠갔는데도 0행이면 판 번호가 그 사이 올라간 것이다. 덮지 않는다.
                alreadyResolved = true;
                log.info("폐기 시점에 이미 처리된 정리안: courseId={}, proposalId={}",
                        courseId, locked.getProposalId());
            }
        }

        return toResponse(userId, course, null, null).toBuilder()
                .alreadyResolved(alreadyResolved ? Boolean.TRUE : null)
                .build();
    }

    /** 프로젝트가 보관되거나 지워질 때. 도는 작업만 무효화하고 정리안 이력은 남긴다. */
    public void cancelForCourse(Long userId, Long courseId, String reason) {
        tidyMapper.cancelOpenJobs(courseId, userId, reason);
    }

    // ===== 응답 만들기 =====

    ProjectTidyResponse toResponse(Long userId, Course course, ProjectTidyProposal proposal, ProjectTidyJob job) {
        Long courseId = course.getCourseId();
        long currentTreeVersion = course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion();
        ProjectTidyInputBuilder.Preview preview = inputBuilder.preview(userId, courseId);
        int legacy = legacyProposalMapper.countSuperseded(courseId, userId);

        ProjectTidyResponse.ProjectTidyResponseBuilder builder = ProjectTidyResponse.builder()
                .courseId(courseId)
                .currentTreeVersion(currentTreeVersion)
                .readyMaterialCount(preview.readyCount())
                .analyzingMaterialCount(preview.analyzingCount())
                .legacyProposalCount(legacy)
                .job(job == null ? null : ProjectTidyResponse.Job.builder()
                        .jobId(job.getJobId()).status(job.getStatus().name())
                        .errorCode(job.getErrorCode()).message(job.getErrorMessage())
                        .createdAt(job.getCreatedAt())
                        .retryable(job.getStatus() == TidyJobStatus.FAILED)
                        .build());

        if (proposal == null) {
            return builder.firstTime(tidyMapper.findLatestJobByCourse(courseId, userId) == null)
                    .groups(List.of()).changes(List.of()).dependsOn(Map.of()).edits(Map.of())
                    .editRevision(0L)
                    .scope(ProjectTidyScope.empty(courseId, currentTreeVersion))
                    .build();
        }

        List<TopicChangeOp> ops = readOps(proposal.getOpsJson());
        ProjectTidyScope scope = readScope(proposal.getScopeJson(), courseId, currentTreeVersion);
        ProjectTidyEdits edits = tidyMapper.findEdits(proposal.getProposalId(), userId);
        Map<String, EditValue> editValues = readEdits(edits);
        Map<Long, MaterialSection> sections = sectionsOf(userId, proposal);
        Map<Long, CourseMaterial> materials = materialsOf(userId, scope);
        Map<Long, CourseTopic> topics = new HashMap<>();
        for (CourseTopic topic : topicMapper.findByCourseIdAndUserIdIncludingArchived(courseId, userId)) {
            topics.put(topic.getTopicId(), topic);
        }

        List<ProjectTidyResponse.Change> changes = new ArrayList<>();
        Map<String, List<String>> dependsOn = TopicChangePlan.dependencies(ops);
        for (TopicChangeOp op : ops) {
            changes.add(describe(op, topics, sections, materials, dependsOn));
        }
        TopicChangeOpsValidator.Summary counts = countOf(ops);

        return builder
                .proposalId(proposal.getProposalId())
                .status(proposal.getStatus().name())
                .revision(proposal.getRevision())
                .baseTreeVersion(proposal.getBaseTreeVersion())
                .treeChanged(proposal.getStatus() == TidyProposalStatus.PROPOSED
                        && proposal.getBaseTreeVersion() != null
                        && proposal.getBaseTreeVersion() != currentTreeVersion)
                .summary(summaryOf(proposal, counts, scope))
                .groups(groupsOf(ops, topics))
                .changes(changes)
                .dependsOn(dependsOn)
                .edits(toEditResponse(editValues))
                .editRevision(edits == null ? 0 : edits.getEditRevision())
                .scope(scope)
                .newMaterialCount(newMaterialCount(scope, preview))
                .firstTime(false)
                .createdAt(proposal.getCreatedAt())
                .resolvedAt(proposal.getResolvedAt())
                .build();
    }

    /** 정리안을 만든 뒤 분석이 끝나 이제 쓸 수 있게 된 자료 수. 몰래 섞지 않고 세기만 한다. */
    private static int newMaterialCount(ProjectTidyScope scope, ProjectTidyInputBuilder.Preview preview) {
        Set<Long> inProposal = new HashSet<>();
        scope.reviewed().forEach(m -> inProposal.add(m.materialId()));
        int n = 0;
        for (ProjectTidyInputBuilder.Preview.Ready ready : preview.ready()) {
            if (!inProposal.contains(ready.materialId())) {
                n++;
            }
        }
        return n;
    }

    private ProjectTidyResponse.Summary summaryOf(ProjectTidyProposal proposal,
                                                  TopicChangeOpsValidator.Summary counts,
                                                  ProjectTidyScope scope) {
        List<String> parts = new ArrayList<>();
        if (counts.link() > 0) {
            parts.add("자료 연결 " + counts.link() + "곳");
        }
        if (counts.add() > 0) {
            parts.add("새 항목 " + counts.add() + "개");
        }
        if (counts.rename() > 0) {
            parts.add("이름 보완 " + counts.rename() + "개");
        }
        if (counts.move() > 0) {
            parts.add("이동 " + counts.move() + "개");
        }
        if (counts.merge() > 0) {
            parts.add("병합 " + counts.merge() + "건");
        }
        if (counts.split() > 0) {
            parts.add("분할 " + counts.split() + "건");
        }
        String headline = parts.isEmpty() ? "바꿀 것이 없었어요" : String.join(" · ", parts);
        return ProjectTidyResponse.Summary.builder()
                .headline(headline)
                .link(counts.link()).add(counts.add()).rename(counts.rename())
                .move(counts.move()).merge(counts.merge()).split(counts.split())
                .total(counts.total()).structural(counts.structural())
                .reviewedMaterialCount(scope.reviewed().size())
                .excludedMaterialCount(scope.excluded().size())
                .build();
    }

    private static TopicChangeOpsValidator.Summary countOf(List<TopicChangeOp> ops) {
        int link = 0, add = 0, rename = 0, move = 0, merge = 0, split = 0;
        for (TopicChangeOp op : ops) {
            switch (op.op() == null ? "" : op.op()) {
                case TopicChangeOp.LINK -> link++;
                case TopicChangeOp.ADD -> add++;
                case TopicChangeOp.RENAME -> rename++;
                case TopicChangeOp.MOVE -> move++;
                case TopicChangeOp.MERGE -> merge++;
                case TopicChangeOp.SPLIT -> split++;
                default -> {
                }
            }
        }
        return new TopicChangeOpsValidator.Summary(link, add, rename, move, merge, split,
                move + merge + split > 0, 0);
    }

    /**
     * 영향을 받는 항목으로 묶는다. 파일이 아니라 항목이 단위다 — 사용자가 판단하는 것은
     * "스택이 어떻게 되는가"이지 "3번 파일이 무엇을 제안했는가"가 아니다.
     */
    private List<ProjectTidyResponse.Group> groupsOf(List<TopicChangeOp> ops, Map<Long, CourseTopic> topics) {
        Map<String, ProjectTidyResponse.Group.GroupBuilder> builders = new LinkedHashMap<>();
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (TopicChangeOp op : ops) {
            Long topicId = groupTopicId(op);
            String key = topicId != null ? "t" + topicId : "n" + op.changeId();
            builders.computeIfAbsent(key, k -> {
                CourseTopic topic = topicId == null ? null : topics.get(topicId);
                CourseTopic parent = topic == null || topic.getParentTopicId() == null
                        ? null : topics.get(topic.getParentTopicId());
                return ProjectTidyResponse.Group.builder()
                        .key(k)
                        .kind(topic != null ? "EXISTING" : "NEW")
                        .topicId(topic == null ? null : topic.getTopicId())
                        .title(topic != null ? topic.getTitle()
                                : op.title() != null ? op.title() : "새 항목")
                        .parentTitle(parent == null ? null : parent.getTitle());
            });
            members.computeIfAbsent(key, k -> new ArrayList<>()).add(op.changeId());
        }
        List<ProjectTidyResponse.Group> out = new ArrayList<>();
        builders.forEach((key, builder) -> out.add(builder.changeIds(members.get(key)).build()));
        return out;
    }

    private static Long groupTopicId(TopicChangeOp op) {
        if (op.survivingTopicId() != null) {
            return op.survivingTopicId();
        }
        if (op.topicId() != null) {
            return op.topicId();
        }
        // 새 항목은 부모 아래로 묶는다. 부모도 새 항목이면 자기 자신이 묶음이 된다.
        return op.parentTopicId();
    }

    private ProjectTidyResponse.Change describe(TopicChangeOp op, Map<Long, CourseTopic> topics,
                                                Map<Long, MaterialSection> sections,
                                                Map<Long, CourseMaterial> materials,
                                                Map<String, List<String>> dependsOn) {
        boolean titleEditable = TopicChangeOp.ADD.equals(op.op()) || TopicChangeOp.RENAME.equals(op.op());
        List<ProjectTidyResponse.Section> briefs = new ArrayList<>();
        Set<Long> sectionIds = new LinkedHashSet<>();
        collectSectionIds(List.of(op), sectionIds);
        for (Long sectionId : sectionIds) {
            MaterialSection section = sections.get(sectionId);
            if (section == null) {
                continue;
            }
            CourseMaterial material = materials.get(section.getMaterialId());
            briefs.add(ProjectTidyResponse.Section.builder()
                    .sectionId(section.getSectionId())
                    .materialId(section.getMaterialId())
                    .materialFilename(material == null ? null : material.getOriginalFilename())
                    .locator(section.locator())
                    .title(section.getDisplayTitle())
                    .roles(readStrings(section.getRolesJson()))
                    .taskText(section.getTaskText())
                    .excerpt(section.getExcerpt())
                    .build());
        }
        return ProjectTidyResponse.Change.builder()
                .changeId(op.changeId())
                .op(op.op())
                .label(labelOf(op.op()))
                .text(TidyChangeText.describe(op, topics, sections, materials))
                .reason(op.reason())
                .titleEditable(titleEditable)
                .title(op.title())
                .structural(op.isStructural())
                .dependsOn(dependsOn.getOrDefault(op.changeId(), List.of()))
                .caution(TidyChangeText.caution(op))
                .sections(briefs)
                .build();
    }

    private static String labelOf(String op) {
        return switch (op == null ? "" : op) {
            case TopicChangeOp.LINK -> "자료 연결";
            case TopicChangeOp.ADD -> "새 항목";
            case TopicChangeOp.RENAME -> "이름 보완";
            case TopicChangeOp.MOVE -> "위치 이동";
            case TopicChangeOp.MERGE -> "병합";
            case TopicChangeOp.SPLIT -> "분할";
            default -> "변경";
        };
    }

    private Map<Long, MaterialSection> sectionsOf(Long userId, ProjectTidyProposal proposal) {
        List<Long> materialIds = tidyMapper.findProposalMaterials(proposal.getProposalId(), userId).stream()
                .filter(ProjectTidyProposalMaterial::isIncluded)
                .map(ProjectTidyProposalMaterial::getMaterialId).toList();
        Map<Long, MaterialSection> out = new HashMap<>();
        if (materialIds.isEmpty()) {
            return out;
        }
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(materialIds, userId)) {
            out.put(section.getSectionId(), section);
        }
        return out;
    }

    private Map<Long, CourseMaterial> materialsOf(Long userId, ProjectTidyScope scope) {
        List<Long> ids = new ArrayList<>();
        scope.reviewed().forEach(m -> ids.add(m.materialId()));
        Map<Long, CourseMaterial> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (CourseMaterial material : courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(ids, userId)) {
            out.put(material.getMaterialId(), material);
        }
        return out;
    }

    private Map<String, ProjectTidyResponse.Edit> toEditResponse(Map<String, EditValue> edits) {
        Map<String, ProjectTidyResponse.Edit> out = new LinkedHashMap<>();
        edits.forEach((changeId, value) -> out.put(changeId, ProjectTidyResponse.Edit.builder()
                .excluded(value.excluded()).title(value.title()).needsConfirm(value.needsConfirm())
                .carriedFrom(value.carriedFrom() == null ? null : ProjectTidyResponse.CarriedFrom.builder()
                        .changeId(value.carriedFrom().changeId())
                        .text(value.carriedFrom().text())
                        .reason(value.carriedFrom().reason())
                        .build())
                .build()));
        return out;
    }

    private String describeMissing(List<TopicChangeOp> ops, Map<String, List<String>> missing) {
        Map<String, String> titles = new HashMap<>();
        ops.forEach(op -> titles.put(op.changeId(), op.title() == null ? labelOf(op.op()) : op.title()));
        List<String> parts = new ArrayList<>();
        missing.forEach((changeId, required) -> required.forEach(id ->
                parts.add("「" + titles.getOrDefault(changeId, "변경") + "」은(는) 「"
                        + titles.getOrDefault(id, "변경") + "」이(가) 있어야 해요")));
        return String.join(" · ", parts);
    }

    // ===== 작은 도구들 =====

    Set<String> changeIds(ProjectTidyProposal proposal) {
        Set<String> out = new HashSet<>();
        readOps(proposal.getOpsJson()).forEach(op -> out.add(op.changeId()));
        return out;
    }

    private static String titleOverride(TopicChangeOp op, Map<String, EditValue> saved,
                                        Map<String, String> requested) {
        if (!TopicChangeOp.ADD.equals(op.op()) && !TopicChangeOp.RENAME.equals(op.op())) {
            return null;
        }
        String fromRequest = requested == null ? null : trimToNull(requested.get(op.changeId()));
        if (fromRequest != null) {
            return fromRequest;
        }
        EditValue value = saved.get(op.changeId());
        return value == null ? null : trimToNull(value.title());
    }

    static void collectSectionIds(List<TopicChangeOp> ops, Set<Long> out) {
        for (TopicChangeOp op : ops) {
            if (op.sectionIds() != null) {
                out.addAll(op.sectionIds());
            }
            if (op.children() != null) {
                collectSectionIds(op.children(), out);
            }
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    List<TopicChangeOp> readOps(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, OPS);
        } catch (Exception e) {
            return List.of();
        }
    }

    Map<String, EditValue> readEdits(ProjectTidyEdits edits) {
        if (edits == null || edits.getEditsJson() == null) {
            return Map.of();
        }
        try {
            Map<String, EditValue> value = objectMapper.readValue(edits.getEditsJson(), EDITS);
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    ProjectTidyScope readScope(String json, Long courseId, long treeVersion) {
        try {
            return json == null ? ProjectTidyScope.empty(courseId, treeVersion)
                    : objectMapper.readValue(json, ProjectTidyScope.class);
        } catch (Exception e) {
            return ProjectTidyScope.empty(courseId, treeVersion);
        }
    }

    private List<String> readStrings(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }
}
