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
import com.jungwoo.project.memo.material.domain.MaterialSection;
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
        Set<Long> allowed = allowedMaterials(job);
        ProjectTidyInputBuilder.Input input = inputBuilder.build(job.getUserId(), course, allowed);
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
             */
            ProjectTidyProposal previous = tidyMapper.findOpenProposalByCourse(job.getCourseId(), job.getUserId());
            if (previous != null) {
                tidyMapper.updateProposalStatus(previous.getProposalId(), job.getUserId(),
                        TidyProposalStatus.SUPERSEDED.name(), null, null, LocalDateTime.now());
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
     * <p>같은 changeId면 뜻이 같은 변경이므로 그대로 승계한다. id가 사라졌지만 같은 항목에 대한
     * 같은 종류의 변경이 있으면 옮기되 <b>확인 필요</b>로 표시한다 — 근거 구간이 달라졌을 수 있고,
     * 그것을 사용자가 모른 채 적용하면 안 된다. 어느 쪽에도 맞지 않으면 옮기지 않는다.
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
        Map<String, TopicChangeOp> byLooseKey = new HashMap<>();
        for (TopicChangeOp op : nextOps) {
            byChangeId.put(op.changeId(), op);
            byLooseKey.putIfAbsent(looseKey(op), op);
        }
        List<TopicChangeOp> previousOps = readOps(previous.getOpsJson());
        Map<String, TopicChangeOp> previousById = new HashMap<>();
        previousOps.forEach(op -> previousById.put(op.changeId(), op));

        Map<String, ProjectTidyService.EditValue> carried = new LinkedHashMap<>();
        int uncertain = 0;
        for (Map.Entry<String, ProjectTidyService.EditValue> entry : oldEdits.entrySet()) {
            ProjectTidyService.EditValue value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (byChangeId.containsKey(entry.getKey())) {
                carried.put(entry.getKey(), value);
                continue;
            }
            TopicChangeOp before = previousById.get(entry.getKey());
            TopicChangeOp match = before == null ? null : byLooseKey.get(looseKey(before));
            if (match != null && !carried.containsKey(match.changeId())) {
                carried.put(match.changeId(),
                        new ProjectTidyService.EditValue(value.excluded(), value.title(), true));
                uncertain++;
            }
        }
        if (carried.isEmpty()) {
            return;
        }
        tidyMapper.insertEditsIgnore(ProjectTidyEdits.builder()
                .proposalId(next.getProposalId()).userId(userId).editsJson(writeJson(carried)).build());
        log.info("검토 편집 승계: proposalId={} → {}, {}건(확인 필요 {}건)",
                previous.getProposalId(), next.getProposalId(), carried.size(), uncertain);
    }

    /** 근거 구간을 뺀 "무엇을 어떻게"만의 키. 정확한 대응이 없을 때의 느슨한 짝짓기에 쓴다. */
    private static String looseKey(TopicChangeOp op) {
        return op.op() + "|" + op.topicId() + "|" + op.survivingTopicId() + "|"
                + (op.title() == null ? "" : op.title().trim());
    }

    private Set<Long> allowedMaterials(ProjectTidyJob job) {
        if (job.getInputSnapshotJson() == null) {
            return null;
        }
        try {
            ProjectTidyService.InputSnapshot snapshot =
                    objectMapper.readValue(job.getInputSnapshotJson(), SNAPSHOT);
            return snapshot == null || snapshot.materialIds() == null
                    ? null : new HashSet<>(snapshot.materialIds());
        } catch (Exception e) {
            return null;
        }
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
