package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangeOpsValidator;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyEdits;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.learning.tidy.domain.TidyExcludeReason;
import com.jungwoo.project.memo.learning.tidy.domain.TidyJobStatus;
import com.jungwoo.project.memo.learning.tidy.domain.TidyProposalStatus;
import com.jungwoo.project.memo.material.analysis.AnalysisFailure;
import com.jungwoo.project.memo.material.analysis.AnalysisFailureClassifier;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 선점된 정리 작업 하나를 끝까지 처리한다. 입력을 모으고, 모델을 부르고, 검증한 뒤 정리안을 저장한다.
 *
 * <p>저장은 {@link ProjectTidyResultWriter}가 작업 행을 잠근 채 한 트랜잭션에서 한다 — 모델이
 * 답하는 동안 사용자가 버렸거나 다른 요청이 들어왔으면 아무것도 남기지 않는다. 그것이 "버린 안이
 * 되살아나지 않는다"를 지키는 자리다.
 *
 * <p>재시도 정책은 자료 분석과 같은 모양이다: 인증 실패는 재시도하지 않고, 한도는 뒤로 미루고,
 * 일시적 실패와 응답 파싱 실패는 시도 횟수 안에서 다시 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectTidyWorker {

    private static final TypeReference<ProjectTidyService.InputSnapshot> SNAPSHOT = new TypeReference<>() {
    };

    /** 모델을 부르기 직전에 임대를 이만큼 연장한다. 호출이 긴 편이라 그 사이 회수되면 안 된다. */
    private static final int LEASE_RENEW_SECONDS = 240;

    public enum Result { COMPLETED, RESCHEDULED, FAILED, AUTH_FAILURE, LOST }

    private final ProjectTidyMapper tidyMapper;
    private final ProjectTidyInputBuilder inputBuilder;
    private final ProjectTidyAnalyzer analyzer;
    private final ProjectTidyResultWriter resultWriter;
    private final CourseMapper courseMapper;
    private final ObjectMapper objectMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialSectionMapper sectionMapper;

    public Result run(ProjectTidyJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            return runOnce(job);
        } catch (AnalysisFailure failure) {
            return handleFailure(job, failure);
        } catch (Exception e) {
            log.error("프로젝트 정리 중 처리되지 않은 예외: jobId={}", job.getJobId(), e);
            return handleFailure(job, new AnalysisFailure(AnalysisFailureClassifier.classify(e),
                    e.getClass().getSimpleName(), e));
        } finally {
            log.info("프로젝트 정리 작업 종료: jobId={}, courseId={}, {}ms",
                    job.getJobId(), job.getCourseId(), System.currentTimeMillis() - startedAt);
        }
    }

    private Result runOnce(ProjectTidyJob job) {
        Course course = courseMapper.findByIdAndUserId(job.getCourseId(), job.getUserId());
        if (course == null || course.getStatus() == CourseStatus.ARCHIVED) {
            tidyMapper.finishJob(job.getJobId(), job.getLeaseToken(), TidyJobStatus.CANCELLED.name(), null,
                    "INVALID_TARGET", "프로젝트가 없거나 보관됐다", LocalDateTime.now());
            return Result.COMPLETED;
        }
        /*
         * 요청 때 고정한 입력을 읽고, 지금 상태가 그것과 같은지 확인한다. 다르면 멈춘다.
         *
         * 예전에는 스냅샷을 못 읽으면 null(=제한 없음)을 돌려 프로젝트의 최신 자료 전부를 읽었다.
         * 실패가 범위 확대로 바뀌는 자리였다. 이제는 읽지 못하면 실패로 끝내고 사유를 남긴다.
         */
        SnapshotCheck check = checkSnapshot(job, course);
        if (check.failure() != null) {
            tidyMapper.finishJob(job.getJobId(), job.getLeaseToken(), TidyJobStatus.FAILED.name(), null,
                    check.failure(), check.message(), LocalDateTime.now());
            log.info("정리 입력이 요청 때와 달라 멈춤: jobId={}, courseId={}, 사유={}",
                    job.getJobId(), job.getCourseId(), check.failure());
            return Result.FAILED;
        }
        ProjectTidyInputBuilder.Input input = withRequestExclusions(
                inputBuilder.build(job.getUserId(), course, check.allowed()), check.snapshot());
        if (input.isEmpty()) {
            // 요청 시점에는 쓸 자료가 있었는데 그 사이 전부 사라졌다. 실패가 아니라 "바꿀 것이 없음"이다.
            return save(job, course, input, List.of(), "정리에 쓸 수 있는 자료가 없어요", null);
        }
        if (tidyMapper.renewJobLease(job.getJobId(), job.getLeaseToken(),
                LocalDateTime.now().plusSeconds(LEASE_RENEW_SECONDS)) != 1) {
            return Result.LOST;
        }
        ProjectTidyAnalyzer.Draft draft = analyzer.analyze(job.getUserId(), input);

        Set<Long> sectionIds = input.sections().stream()
                .map(MaterialSection::getSectionId).collect(Collectors.toSet());
        TopicChangeOpsValidator.Result validated = TopicChangeOpsValidator.validate(
                TopicChangePlan.order(draft.ops()), input.topics(), sectionIds, false);
        if (!validated.rejected().isEmpty()) {
            log.info("정리안 검증에서 버린 작업 {}건: jobId={}, 사유={}",
                    validated.rejected().size(), job.getJobId(), validated.rejected());
        }
        TopicChangePlan.Plan plan = TopicChangePlan.of(validated.ops());
        return save(job, course, input, plan.ops(), draft.summary(), draft.model());
    }

    /**
     * 결과를 저장한다. 작업 행을 잠근 채 임대를 대조하므로, 이 사이에 사용자가 버렸으면
     * 아무것도 남지 않는다.
     */
    private Result save(ProjectTidyJob job, Course course, ProjectTidyInputBuilder.Input input,
                        List<TopicChangeOp> ops, String summary, String model) {
        boolean written = resultWriter.writeIfLeased(job, () -> {
            ProjectTidyProposal proposal = ProjectTidyProposal.builder()
                    .userId(job.getUserId()).courseId(job.getCourseId()).jobId(job.getJobId())
                    .generation(job.getGeneration())
                    .revision(1L)
                    .baseTreeVersion(course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion())
                    .status(ops.isEmpty() ? TidyProposalStatus.EMPTY : TidyProposalStatus.PROPOSED)
                    .summaryJson(writeJson(Map.of("summary", summary == null ? "" : summary)))
                    .opsJson(writeJson(ops))
                    .scopeJson(writeJson(input.scope()))
                    .previousProposalId(job.getPreviousProposalId())
                    .model(model)
                    .build();

            /*
             * 앞선 정리안은 <새 것이 저장되는 이 순간에> 물러난다. 모델 호출이 실패했으면 여기까지
             * 오지 않으므로 기존 정리안과 사용자 편집은 그대로 남는다 — "새 버전 생성 실패 → 기존 안 유지".
             *
             * 두 번 UPDATE하는 이유: 먼저 상태를 옮겨야 "프로젝트당 열린 정리안 하나" 유일 인덱스가
             * 풀려 새 행이 들어간다. 새 행의 id는 그때 정해지므로 superseded_by는 그 뒤에 적는다.
             *
             * 전이는 <조건부>다. 모델이 도는 동안 사용자가 앞 판을 적용했을 수 있고, 그때 조건
             * 없이 SUPERSEDED로 덮으면 적용 이력이 사라진다 — 트리는 바뀌었는데 "물러난 안"만
             * 남는다. 0행이면 앞 판은 이미 끝난 것이므로 승계할 편집도 없다.
             */
            ProjectTidyProposal previous = lockOpenPrevious(job);
            boolean supersededPrevious = previous != null
                    && tidyMapper.supersedeProposalIfOpen(previous.getProposalId(), job.getUserId(),
                            null, LocalDateTime.now()) == 1;
            if (previous != null && !supersededPrevious) {
                log.info("앞 정리안이 그 사이 끝나 승계하지 않는다: proposalId={}, 상태={}",
                        previous.getProposalId(), previous.getStatus());
                previous = null;
            }
            tidyMapper.insertProposal(proposal);
            saveMaterials(proposal, input);
            if (previous != null) {
                tidyMapper.updateProposalStatus(previous.getProposalId(), job.getUserId(),
                        TidyProposalStatus.SUPERSEDED.name(), null, proposal.getProposalId(), LocalDateTime.now());
                carryOverEdits(job.getUserId(), previous, proposal, ops);
            }
            tidyMapper.finishJob(job.getJobId(), job.getLeaseToken(), TidyJobStatus.DONE.name(),
                    proposal.getProposalId(), null, null, LocalDateTime.now());
        });
        if (!written) {
            log.info("정리안을 버렸다 — 그 사이 요청이 취소되거나 갈아탔다: jobId={}", job.getJobId());
            return Result.LOST;
        }
        return Result.COMPLETED;
    }

    /**
     * 앞 정리안을 잠근 채 돌려준다. 잠금 순서는 적용·폐기와 같다(정리안 → 작업 → 자료).
     *
     * <p>{@code writeIfLeased}가 이미 작업 행을 잡고 있지만, 그것은 "이 작업이 아직 내
     * 것인가"를 보는 자물쇠이지 정리안 행을 지켜 주지는 않는다. 앞 판의 상태는 따로 잠가야
     * 읽은 뒤 쓰기 전에 바뀌는 것을 막는다.
     */
    private ProjectTidyProposal lockOpenPrevious(ProjectTidyJob job) {
        ProjectTidyProposal open = tidyMapper.findOpenProposalByCourse(job.getCourseId(), job.getUserId());
        if (open == null) {
            return null;
        }
        ProjectTidyProposal locked = tidyMapper.findProposalByIdForUpdate(open.getProposalId(), job.getUserId());
        return locked != null && locked.getStatus() == TidyProposalStatus.PROPOSED ? locked : null;
    }

    private void saveMaterials(ProjectTidyProposal proposal, ProjectTidyInputBuilder.Input input) {
        for (ProjectTidyScope.Member member : input.scope().reviewed()) {
            tidyMapper.insertProposalMaterial(ProjectTidyProposalMaterial.builder()
                    .proposalId(proposal.getProposalId()).userId(proposal.getUserId())
                    .materialId(member.materialId()).fileHash(member.fileHash())
                    .analysisVersion(member.analysisVersion())
                    .sectionCount(member.sectionCount()).reviewedCount(member.reviewedCount())
                    .included(true).build());
        }
        Set<Long> seen = new HashSet<>();
        input.scope().reviewed().forEach(m -> seen.add(m.materialId()));
        for (ProjectTidyScope.Excluded excluded : input.scope().excluded()) {
            if (excluded.materialId() == null || !seen.add(excluded.materialId())) {
                continue;
            }
            tidyMapper.insertProposalMaterial(ProjectTidyProposalMaterial.builder()
                    .proposalId(proposal.getProposalId()).userId(proposal.getUserId())
                    .materialId(excluded.materialId()).sectionCount(0).reviewedCount(0)
                    .included(false).excludeReason(parseReason(excluded.reason())).build());
        }
    }

    private static TidyExcludeReason parseReason(String name) {
        try {
            return name == null ? null : TidyExcludeReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 앞 판에서 사용자가 고친 것을 새 판으로 옮긴다.
     *
     * <p>세 갈래로 나눈다.
     * <ol>
     *   <li><b>확실한 승계</b>: changeId가 같고 <i>내용도</i> 같다. 그대로 옮긴다.</li>
     *   <li><b>확인 필요</b>: 대응은 찾았지만 같다고 단정할 수 없다. 옮기되 표시하고,
     *       사용자가 고르기 전에는 적용을 막는다.</li>
     *   <li><b>옮기지 않음</b>: 대응이 없거나, 후보가 여럿이라 어느 것인지 알 수 없다.</li>
     * </ol>
     *
     * <p><b>왜 changeId만으로는 부족한가.</b> changeId는 "같은 것을 가리키는가"의 이름표라
     * 일부러 좁다 — RENAME은 대상 항목만, SPLIT은 쪼갤 항목만 보고 만든다. 그래서 같은
     * 항목을 <i>전혀 다른 이름</i>으로 바꾸자는 새 제안, 자식 구성이 완전히 달라진 분할,
     * 근거 구간이 바뀐 연결이 모두 앞 판과 같은 이름표를 받는다. 거기에 옛 판단을 말없이
     * 붙이면, 사용자는 보지도 않은 제안을 자기가 승인한 것으로 적용하게 된다.
     * {@link TopicChangePlan#contentKey}로 한 번 더 본다.
     *
     * <p>느슨한 짝짓기에서 후보가 여럿이면 <b>아무것도 옮기지 않는다</b>. 예전에는 첫 번째
     * 후보에 붙였는데, 그것은 맞을 확률이 절반인 추측이었다. 옮기지 않으면 사용자가 다시
     * 고치면 그만이지만, 잘못 붙으면 엉뚱한 변경이 제외된 채 적용된다.
     */
    private void carryOverEdits(Long userId, ProjectTidyProposal previous, ProjectTidyProposal next,
                                List<TopicChangeOp> nextOps) {
        ProjectTidyEdits old = tidyMapper.findEdits(previous.getProposalId(), userId);
        if (old == null || old.getEditsJson() == null) {
            return;
        }
        Map<String, ProjectTidyService.EditValue> oldEdits;
        try {
            oldEdits = objectMapper.readValue(old.getEditsJson(),
                    new TypeReference<Map<String, ProjectTidyService.EditValue>>() {
                    });
        } catch (Exception e) {
            return;
        }
        if (oldEdits == null || oldEdits.isEmpty()) {
            return;
        }

        Map<String, TopicChangeOp> byChangeId = new HashMap<>();
        Map<String, List<TopicChangeOp>> byLooseKey = new HashMap<>();
        for (TopicChangeOp op : nextOps) {
            byChangeId.put(op.changeId(), op);
            byLooseKey.computeIfAbsent(looseKey(op), k -> new ArrayList<>()).add(op);
        }
        Map<String, TopicChangeOp> previousById = new HashMap<>();
        readOps(previous.getOpsJson()).forEach(op -> previousById.put(op.changeId(), op));

        Map<String, ProjectTidyService.EditValue> carried = new LinkedHashMap<>();
        int uncertain = 0;
        int dropped = 0;
        for (Map.Entry<String, ProjectTidyService.EditValue> entry : oldEdits.entrySet()) {
            ProjectTidyService.EditValue value = entry.getValue();
            if (value == null) {
                continue;
            }
            TopicChangeOp before = previousById.get(entry.getKey());
            Match match = findMatch(entry.getKey(), before, byChangeId, byLooseKey, carried.keySet());
            if (match == null) {
                dropped++;
                continue;
            }
            /*
             * 앞 판에서 이미 "확인 필요"였던 편집은 확인하지 않은 채 또 옮겨진다. 그때도
             * 확인 필요로 남긴다 — 판이 두 번 바뀌었다고 저절로 확인되지는 않는다.
             */
            boolean needsConfirm = match.uncertain() || value.needsConfirm();
            ProjectTidyService.CarriedFrom from = needsConfirm
                    ? new ProjectTidyService.CarriedFrom(entry.getKey(),
                            TidyChangeText.shortName(before), match.reason())
                    : null;
            carried.put(match.op().changeId(),
                    new ProjectTidyService.EditValue(value.excluded(), value.title(), needsConfirm, from));
            if (needsConfirm) {
                uncertain++;
            }
        }
        if (carried.isEmpty()) {
            return;
        }
        tidyMapper.insertEditsIgnore(ProjectTidyEdits.builder()
                .proposalId(next.getProposalId()).userId(userId).editsJson(writeJson(carried)).build());
        log.info("검토 편집 승계: proposalId={} → {}, {}건(확인 필요 {}건, 옮기지 않음 {}건)",
                previous.getProposalId(), next.getProposalId(), carried.size(), uncertain, dropped);
    }

    /**
     * 테스트에서 승계만 따로 부른다. 모델 호출 없이 "판이 바뀌었을 때 편집을 어떻게 옮기는가"만
     * 보려면 이 단계가 따로 열려 있어야 한다.
     */
    void carryOverEditsForTest(Long userId, ProjectTidyProposal previous, ProjectTidyProposal next,
                               List<TopicChangeOp> nextOps) {
        carryOverEdits(userId, previous, next, nextOps);
    }

    /** 승계 대상 하나를 찾은 결과. uncertain이면 사용자 확인이 필요하다. */
    private record Match(TopicChangeOp op, boolean uncertain, String reason) {
    }

    private Match findMatch(String oldChangeId, TopicChangeOp before,
                            Map<String, TopicChangeOp> byChangeId,
                            Map<String, List<TopicChangeOp>> byLooseKey,
                            Set<String> alreadyTaken) {
        TopicChangeOp sameId = byChangeId.get(oldChangeId);
        if (sameId != null) {
            if (before != null
                    && TopicChangePlan.contentKey(before).equals(TopicChangePlan.contentKey(sameId))) {
                return new Match(sameId, false, null);
            }
            // 이름표는 같은데 내용이 달라졌다. 무엇이 달라졌는지 말해 준다.
            return new Match(sameId, true, changedWhat(before, sameId));
        }
        if (before == null) {
            return null;
        }
        List<TopicChangeOp> candidates = byLooseKey.getOrDefault(looseKey(before), List.of()).stream()
                .filter(op -> !alreadyTaken.contains(op.changeId()))
                .toList();
        if (candidates.size() != 1) {
            // 0이면 대응이 없고, 2 이상이면 어느 것인지 알 수 없다. 둘 다 옮기지 않는다.
            return null;
        }
        return new Match(candidates.get(0), true, "같은 항목에 대한 비슷한 제안으로 옮겼어요");
    }

    /** 같은 이름표인데 내용이 달라진 이유를 사람 말로. 화면이 "무엇을 확인해야 하나"에 답한다. */
    private static String changedWhat(TopicChangeOp before, TopicChangeOp after) {
        if (before == null) {
            return "이전 제안을 찾을 수 없어요";
        }
        List<String> changed = new ArrayList<>();
        if (!java.util.Objects.equals(nullSafe(before.title()), nullSafe(after.title()))) {
            changed.add("제안한 이름");
        }
        if (!sameIds(before.sectionIds(), after.sectionIds())) {
            changed.add("근거 구간");
        }
        if (!java.util.Objects.equals(before.parentTopicId(), after.parentTopicId())
                || !java.util.Objects.equals(before.parentTempId(), after.parentTempId())) {
            changed.add("놓일 위치");
        }
        if (childCount(before) != childCount(after)) {
            changed.add("나눌 구성");
        }
        if (!sameIds(before.absorbedTopicIds(), after.absorbedTopicIds())) {
            changed.add("합칠 대상");
        }
        return changed.isEmpty()
                ? "이전 판의 제안과 같은지 확인해 주세요"
                : String.join("·", changed) + "이(가) 달라졌어요";
    }

    private static int childCount(TopicChangeOp op) {
        return op.children() == null ? 0 : op.children().size();
    }

    private static boolean sameIds(List<Long> a, List<Long> b) {
        List<Long> x = a == null ? List.of() : a.stream().filter(java.util.Objects::nonNull).sorted().toList();
        List<Long> y = b == null ? List.of() : b.stream().filter(java.util.Objects::nonNull).sorted().toList();
        return x.equals(y);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 근거 구간을 뺀 "무엇을 어떻게"만의 키. 정확한 대응이 없을 때의 느슨한 짝짓기에 쓴다. */
    private static String looseKey(TopicChangeOp op) {
        return op.op() + "|" + op.topicId() + "|" + op.survivingTopicId() + "|"
                + (op.title() == null ? "" : op.title().trim());
    }

    /** 스냅샷 확인 결과. failure가 null이면 allowed로 실행한다. */
    private record SnapshotCheck(ProjectTidyService.InputSnapshot snapshot, Set<Long> allowed,
                                 String failure, String message) {
        static SnapshotCheck ok(ProjectTidyService.InputSnapshot snapshot) {
            return new SnapshotCheck(snapshot, new HashSet<>(snapshot.materialIds()), null, null);
        }

        static SnapshotCheck fail(String code, String message) {
            return new SnapshotCheck(null, null, code, message);
        }
    }

    /**
     * 요청 때의 입력이 지금도 그대로인가.
     *
     * <p>바뀐 것이 있으면 <b>최신 입력으로 대신하지 않고</b> 멈춘다. 사용자가 누를 때 본 범위와
     * 다른 입력으로 만든 정리안은, 겉보기에 멀쩡해도 사용자가 요청한 것이 아니다. 최신을
     * 반영하려면 새 요청을 하면 된다 — 화면이 그 버튼을 준다(needsNewRequest).
     *
     * <p>보는 것: 트리 판, 자료마다 (아직 있고, 이 프로젝트에 연결돼 있고, 파일 해시가 같고,
     * 지금 살아 있는 구간 id 집합과 분석 판이 요청 때와 같은가).
     */
    private SnapshotCheck checkSnapshot(ProjectTidyJob job, Course course) {
        ProjectTidyService.InputSnapshot snapshot;
        try {
            snapshot = job.getInputSnapshotJson() == null ? null
                    : objectMapper.readValue(job.getInputSnapshotJson(), SNAPSHOT);
        } catch (Exception e) {
            return SnapshotCheck.fail("SNAPSHOT_INVALID", "요청 당시 입력 기록을 읽지 못했어요. 다시 정리를 요청해 주세요");
        }
        if (snapshot == null) {
            return SnapshotCheck.fail("SNAPSHOT_INVALID", "요청 당시 입력 기록이 없어요. 다시 정리를 요청해 주세요");
        }
        if (snapshot.version() == null || snapshot.version() < ProjectTidyService.SNAPSHOT_VERSION
                || snapshot.materials() == null || snapshot.materialIds() == null || snapshot.treeVersion() == null) {
            return SnapshotCheck.fail("SNAPSHOT_OUTDATED",
                    "예전 방식으로 기록된 요청이라 무엇을 보고 정리할지 확인할 수 없어요. 다시 정리를 요청해 주세요");
        }

        long treeNow = course.getTopicTreeVersion() == null ? 0 : course.getTopicTreeVersion();
        if (treeNow != snapshot.treeVersion()) {
            return SnapshotCheck.fail("STALE_INPUT", "요청한 뒤 학습 구조가 바뀌었어요. 지금 구조로 다시 정리해 주세요");
        }

        List<Long> ids = snapshot.materials().stream().map(ProjectTidyService.SnapshotMaterial::materialId).toList();
        if (ids.isEmpty()) {
            return SnapshotCheck.ok(snapshot);
        }
        Map<Long, CourseMaterial> materials = new HashMap<>();
        for (CourseMaterial m : courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(ids, job.getUserId())) {
            materials.put(m.getMaterialId(), m);
        }
        Set<Long> linked = new HashSet<>();
        for (MaterialLink link : materialLinkMapper.findByCourseIdAndUserId(job.getCourseId(), job.getUserId())) {
            linked.add(link.getMaterialId());
        }
        Map<Long, List<MaterialSection>> sections = new HashMap<>();
        for (MaterialSection s : sectionMapper.findActiveByMaterialIds(ids, job.getUserId())) {
            sections.computeIfAbsent(s.getMaterialId(), k -> new ArrayList<>()).add(s);
        }

        List<String> changed = new ArrayList<>();
        for (ProjectTidyService.SnapshotMaterial want : snapshot.materials()) {
            CourseMaterial now = materials.get(want.materialId());
            String name = want.filename() != null ? want.filename()
                    : now != null ? now.getOriginalFilename() : "자료 " + want.materialId();
            if (now == null || now.getStatus() != MaterialStatus.ACTIVE) {
                changed.add("「" + name + "」 삭제됨");
                continue;
            }
            if (!linked.contains(want.materialId())) {
                changed.add("「" + name + "」 연결 해제됨");
                continue;
            }
            if (want.fileHash() != null && !want.fileHash().equals(now.getFileHash())) {
                changed.add("「" + name + "」 파일이 바뀜");
                continue;
            }
            List<MaterialSection> own = sections.getOrDefault(want.materialId(), List.of());
            List<Long> idsNow = own.stream().map(MaterialSection::getSectionId).sorted().toList();
            List<Long> idsThen = want.sectionIds() == null ? List.of()
                    : want.sectionIds().stream().sorted().toList();
            Integer versionNow = own.stream().map(MaterialSection::getAnalysisVersion)
                    .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(null);
            if (!idsNow.equals(idsThen) || !java.util.Objects.equals(versionNow, want.analysisVersion())) {
                changed.add("「" + name + "」 다시 분석됨");
            }
        }
        if (!changed.isEmpty()) {
            return SnapshotCheck.fail("STALE_INPUT", "요청한 뒤 자료가 바뀌었어요: " + String.join(", ", changed)
                    + ". 지금 자료로 다시 정리해 주세요");
        }
        return SnapshotCheck.ok(snapshot);
    }

    /**
     * 요청 때 빠진 자료와 사유를 최종 범위에 붙인다.
     *
     * <p>예전에는 여기서 사라졌다 — 실행 시점 목록은 "요청 때 허용한 자료"로 좁혀지므로, 그때
     * 분석 중이라 빠진 자료는 제외 목록에도 없었다. 사용자는 정리안이 무엇을 빼고 만든 것인지
     * 알 수 없었다.
     */
    private ProjectTidyInputBuilder.Input withRequestExclusions(ProjectTidyInputBuilder.Input input,
                                                                ProjectTidyService.InputSnapshot snapshot) {
        if (snapshot == null || snapshot.excludedAtRequest() == null || snapshot.excludedAtRequest().isEmpty()) {
            return input;
        }
        ProjectTidyScope scope = input.scope();
        List<ProjectTidyScope.Excluded> excluded = new ArrayList<>(scope.excluded());
        Set<Long> present = new HashSet<>();
        excluded.forEach(e -> present.add(e.materialId()));
        scope.reviewed().forEach(m -> present.add(m.materialId()));
        for (ProjectTidyScope.Excluded e : snapshot.excludedAtRequest()) {
            if (e.materialId() == null || present.add(e.materialId())) {
                excluded.add(e);
            }
        }
        ProjectTidyScope merged = new ProjectTidyScope(scope.courseId(), scope.treeVersion(), scope.topicCount(),
                scope.treeLinesShown(), scope.reviewed(), excluded, scope.truncated(), scope.sectionsTotal(),
                scope.sectionsReviewed());
        return new ProjectTidyInputBuilder.Input(input.course(), input.topics(), input.topicLinks(),
                input.sections(), input.materialsById(), input.assignments(), merged);
    }

    private Result handleFailure(ProjectTidyJob job, AnalysisFailure failure) {
        int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
        int max = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
        log.warn("프로젝트 정리 실패: jobId={}, courseId={}, 종류={}, 시도={}/{}",
                job.getJobId(), job.getCourseId(), failure.kind(), attempt, max);
        switch (failure.kind()) {
            case AUTH -> {
                tidyMapper.finishJob(job.getJobId(), job.getLeaseToken(), TidyJobStatus.UNAVAILABLE.name(), null,
                        "AUTH", "지금은 AI에 연결할 수 없어요", LocalDateTime.now());
                return Result.AUTH_FAILURE;
            }
            case RATE_LIMIT -> {
                long minutes = Math.min(60, 2L << Math.min(5, Math.max(0, attempt - 1)));
                tidyMapper.rescheduleJob(job.getJobId(), job.getLeaseToken(),
                        LocalDateTime.now().plusMinutes(minutes), "RATE_LIMIT", "사용 한도로 잠시 뒤 다시 시도해요");
                return Result.RESCHEDULED;
            }
            default -> {
                if (attempt >= max) {
                    tidyMapper.finishJob(job.getJobId(), job.getLeaseToken(), TidyJobStatus.FAILED.name(), null,
                            failure.kind().name(), "정리안을 만들지 못했어요. 다시 시도할 수 있어요",
                            LocalDateTime.now());
                    return Result.FAILED;
                }
                long seconds = switch (attempt) {
                    case 1 -> 20;
                    case 2 -> 90;
                    default -> 300;
                };
                tidyMapper.rescheduleJob(job.getJobId(), job.getLeaseToken(),
                        LocalDateTime.now().plusSeconds(seconds), failure.kind().name(), "다시 시도하는 중이에요");
                return Result.RESCHEDULED;
            }
        }
    }

    private List<TopicChangeOp> readOps(String json) {
        try {
            return json == null ? List.of()
                    : objectMapper.readValue(json, new TypeReference<List<TopicChangeOp>>() {
                    });
        } catch (Exception e) {
            return new ArrayList<>();
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
