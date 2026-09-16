package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanReviewCategory;
import com.jungwoo.project.memo.plan.dto.PlanReviewMoveFlag;
import com.jungwoo.project.memo.plan.dto.PlanReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 계획 회고. 확정 당시의 스냅샷과 현재 상태를 대조한다.
 *
 * ★ 주 분류는 execution_items.status가 단독으로 결정한다. execution_records는 status=DONE일
 * 때 완료와 일부 진행을 가르는 데만 쓴다.
 *
 * 이 규칙이 필요한 이유는 실데이터에 있다. reopen()은 status를 DONE→PLANNED로 되돌리면서
 * execution_records를 지우지 않는다(이력을 남기는 설계다). 그래서 "COMPLETED 기록이 있는
 * PLANNED 항목"이 정상적으로 존재하고, 기록을 우선하면 되돌린 항목이 완료로 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanReviewService {

    private final PlanVersionMapper planVersionMapper;
    private final PlanVersionService planVersionService;
    private final PlanSnapshotCodec snapshotCodec;
    private final ExecutionItemMapper executionItemMapper;
    private final ExecutionRecordMapper executionRecordMapper;

    @Transactional(readOnly = true)
    public PlanReviewResponse review(Long userId, Long planVersionId) {
        PlanVersion plan = planVersionService.getOwned(userId, planVersionId);
        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(plan.getItemsSnapshot());

        List<Long> snapshotIds = snapshot.stream().map(PlanSnapshotItem::executionItemId).toList();

        // 회고 전용 조회 — is_deleted를 거르지 않는다. "계획에서 뺐어요"를 분류해야 하므로
        // 삭제된 항목도 보여야 한다.
        Map<Long, ExecutionItem> currentById = new HashMap<>();
        if (!snapshotIds.isEmpty()) {
            for (ExecutionItem item : executionItemMapper.findByIdsForReview(userId, snapshotIds)) {
                currentById.put(item.getExecutionItemId(), item);
            }
        }

        List<ExecutionItem> inPeriod = executionItemMapper.findInPeriodForReview(
                userId, plan.getStartDate(), plan.getEndDate());

        // 기록은 스냅샷 항목 + 기간 내 항목 전부에 대해 한 번에 가져온다.
        Set<Long> recordTargets = new HashSet<>(snapshotIds);
        inPeriod.forEach(item -> recordTargets.add(item.getExecutionItemId()));
        Map<Long, List<ExecutionRecord>> recordsByItem = loadRecords(userId, recordTargets);

        List<PlanReviewResponse.PlanReviewItem> items = new ArrayList<>();
        int plannedMinutes = 0;
        int completedMinutes = 0;
        int measuredMinutes = 0;
        int estimatedMinutes = 0;
        int unmeasuredDone = 0;

        for (PlanSnapshotItem planned : snapshot) {
            plannedMinutes += planned.expectedMinutes() != null ? planned.expectedMinutes() : 0;
            ExecutionItem current = currentById.get(planned.executionItemId());
            List<ExecutionRecord> records = recordsByItem.getOrDefault(planned.executionItemId(), List.of());
            ExecutionRecord latest = latestRecord(records);

            PlanReviewCategory category = classify(current, latest);
            String actualSource = "NONE";
            if (category == PlanReviewCategory.DONE || category == PlanReviewCategory.PARTIAL_DONE) {
                /*
                 * 실측과 추정을 가른다. 시간을 적지 않은 완료 항목은 예정 시간으로 세되(예전 화면의 "다 했는데 0시간"을
                 * 막는다) 그것을 실측으로 부르지 않는다 — 학습 속도·원인 분석은 measuredMinutes만 본다.
                 */
                if (latest != null && latest.getActualMinutes() != null) {
                    measuredMinutes += latest.getActualMinutes();
                    completedMinutes += latest.getActualMinutes();
                    actualSource = "MEASURED";
                } else {
                    int estimate = planned.expectedMinutes() != null ? planned.expectedMinutes() : 0;
                    estimatedMinutes += estimate;
                    completedMinutes += estimate;
                    unmeasuredDone++;
                    actualSource = "ESTIMATED";
                }
            }

            items.add(PlanReviewResponse.PlanReviewItem.builder()
                    .executionItemId(planned.executionItemId())
                    .title(current != null ? current.getTitle() : planned.title())
                    .courseId(planned.courseId())
                    .courseTitle(planned.courseTitle())
                    .category(category)
                    .moveFlag(moveFlag(planned, current))
                    .plannedDate(planned.scheduledDate())
                    .currentDate(current != null ? current.getScheduledDate() : null)
                    .expectedMinutes(planned.expectedMinutes())
                    .actualMinutes(latest != null ? latest.getActualMinutes() : null)
                    .actualMinutesSource(actualSource)
                    .recordCount(records.size())
                    .build());
        }

        // 스냅샷 밖 항목: 잔여분(부분 완료로 갈라진 것)과 계획 밖에서 한 일을 나눈다.
        Set<Long> snapshotIdSet = new HashSet<>(snapshotIds);
        Set<Long> leftoverIds = leftoverIdsOf(snapshotIds, recordsByItem);
        for (ExecutionItem item : inPeriod) {
            if (snapshotIdSet.contains(item.getExecutionItemId())) {
                continue;
            }
            boolean leftover = leftoverIds.contains(item.getExecutionItemId());
            List<ExecutionRecord> records = recordsByItem.getOrDefault(item.getExecutionItemId(), List.of());
            items.add(PlanReviewResponse.PlanReviewItem.builder()
                    .executionItemId(item.getExecutionItemId())
                    .title(item.getTitle())
                    .courseId(item.getCourseId())
                    .category(leftover ? PlanReviewCategory.LEFTOVER : PlanReviewCategory.OUTSIDE_PLAN)
                    .currentDate(item.getScheduledDate())
                    .expectedMinutes(item.getExpectedMinutes())
                    .actualMinutes(latestRecord(records) != null ? latestRecord(records).getActualMinutes() : null)
                    .actualMinutesSource(latestRecord(records) != null && latestRecord(records).getActualMinutes() != null
                            ? "MEASURED" : "NONE")
                    .recordCount(records.size())
                    .build());
        }

        return PlanReviewResponse.builder()
                .planVersionId(plan.getPlanVersionId())
                .planKey(plan.getPlanKey())
                .title(plan.getTitle())
                .startDate(plan.getStartDate())
                .endDate(plan.getEndDate())
                .intensity(plan.getIntensity())
                .targetMinutes(plan.getTargetMinutes())
                .plannedMinutes(plannedMinutes)
                .completedMinutes(completedMinutes)
                .measuredMinutes(measuredMinutes)
                .estimatedMinutes(estimatedMinutes)
                .unmeasuredDoneCount(unmeasuredDone)
                .items(items)
                .build();
    }

    /**
     * 다음 계획 초안의 입력으로 쓸 직전 계획 요약. 없으면 null.
     *
     * AI에게 정보로만 준다 — 강도를 자동으로 낮추지 않는다(§0 만들지 않는 것).
     */
    @Transactional(readOnly = true)
    public String summarizeLatestForPrompt(Long userId) {
        PlanVersion latest = planVersionMapper.findLatestConfirmed(userId);
        if (latest == null) {
            return null;
        }
        PlanReviewResponse review = review(userId, latest.getPlanVersionId());
        long done = countOf(review, PlanReviewCategory.DONE) + countOf(review, PlanReviewCategory.PARTIAL_DONE);
        long unplaced = countOf(review, PlanReviewCategory.UNPLACED);
        long remaining = countOf(review, PlanReviewCategory.REMAINING);
        StringBuilder sb = new StringBuilder();
        sb.append(latest.getStartDate()).append("~").append(latest.getEndDate())
                .append(" \"").append(latest.getTitle()).append("\"");
        if (latest.getIntensity() != null) {
            sb.append(" · 강도 ").append(latest.getIntensity());
        }
        if (latest.getTargetMinutes() != null) {
            sb.append(" · 목표 ").append(latest.getTargetMinutes()).append("분");
        }
        /*
         * 실측과 추정을 한 숫자로 뭉치지 않는다. "실제 N분 수행"이라고 적으면 시간을 적지 않은 완료 항목의 예정 시간이
         * 관측된 실제 시간으로 읽히고, 그 위에서 학습 속도·실패 원인이 추정된다.
         */
        sb.append(" · 실제 측정 ").append(review.getMeasuredMinutes() == null ? 0 : review.getMeasuredMinutes()).append("분");
        int unmeasured = review.getUnmeasuredDoneCount() == null ? 0 : review.getUnmeasuredDoneCount();
        if (unmeasured > 0) {
            sb.append(" · 완료했지만 시간 미기록 ").append(unmeasured).append("건(예정 합 ")
                    .append(review.getEstimatedMinutes() == null ? 0 : review.getEstimatedMinutes()).append("분, 추정)");
        }
        sb.append(" (완료 ").append(done)
                .append(", 아직 남음 ").append(remaining)
                .append(", 날짜 미정 ").append(unplaced).append(")");
        return sb.toString();
    }

    // ===== 판정 =====

    /**
     * 주 분류. status가 단독으로 결정하고, 기록은 DONE일 때만 완료/일부를 가른다.
     *
     * status=PLANNED인데 COMPLETED 기록이 있는 경우(reopen된 항목)는 "남아 있음"이다.
     * 기록을 우선하면 사용자가 되돌린 항목이 완료로 보인다.
     */
    private PlanReviewCategory classify(ExecutionItem current, ExecutionRecord latest) {
        if (current == null) {
            // 스냅샷에는 있는데 지금 조회되지 않는다 = 물리 삭제된 경우뿐이다(soft delete는
            // 회고 조회가 걸러내지 않는다). 계획에서 빠진 것으로 본다.
            return PlanReviewCategory.EXCLUDED;
        }
        if (Boolean.TRUE.equals(current.getIsDeleted()) || current.getStatus() == ExecutionStatus.CANCELLED) {
            return PlanReviewCategory.EXCLUDED;
        }
        if (current.getStatus() == ExecutionStatus.HOLD) {
            return PlanReviewCategory.HOLD;
        }
        if (current.getStatus() == ExecutionStatus.DONE) {
            return latest != null && latest.getOutcome() == ExecutionRecordOutcome.PARTIAL
                    ? PlanReviewCategory.PARTIAL_DONE
                    : PlanReviewCategory.DONE;
        }
        // PLANNED / PARTIAL. 날짜가 있으면 남아 있음, 없으면 미배치.
        return current.getPlacementType() == PlacementType.UNSCHEDULED
                ? PlanReviewCategory.UNPLACED
                : PlanReviewCategory.REMAINING;
    }

    /**
     * 부가 플래그. NULL-safe로 비교한다 — 한쪽이 null일 때 != 는 UNKNOWN이 되어
     * 조용히 false로 떨어진다.
     */
    private PlanReviewMoveFlag moveFlag(PlanSnapshotItem planned, ExecutionItem current) {
        if (current == null) {
            return null;
        }
        java.time.LocalDate before = planned.scheduledDate();
        java.time.LocalDate after = current.getScheduledDate();
        if (Objects.equals(before, after)) {
            return null;
        }
        if (before == null) {
            return PlanReviewMoveFlag.SCHEDULED;
        }
        return after == null ? PlanReviewMoveFlag.UNPLACED_AGAIN : PlanReviewMoveFlag.MOVED;
    }

    private Map<Long, List<ExecutionRecord>> loadRecords(Long userId, Set<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ExecutionRecord>> byItem = new HashMap<>();
        for (ExecutionRecord record : executionRecordMapper.findByExecutionItemIds(userId, List.copyOf(itemIds))) {
            byItem.computeIfAbsent(record.getExecutionItemId(), k -> new ArrayList<>()).add(record);
        }
        return byItem;
    }

    /** 항목별 최신 기록. recorded_at DESC LIMIT 1과 같은 선택을 자바에서 한다. */
    private ExecutionRecord latestRecord(List<ExecutionRecord> records) {
        return records.stream()
                .max(Comparator.comparing(ExecutionRecord::getRecordedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /**
     * 잔여분 id 집합. ★ execution_records.remaining_execution_item_id를 1급 근거로 쓴다 —
     * DB가 outcome=PARTIAL일 때만 이 값이 있도록 강제하므로(chk_execution_records_remaining)
     * source_execution_item_id보다 확실하다.
     */
    private Set<Long> leftoverIdsOf(List<Long> snapshotIds, Map<Long, List<ExecutionRecord>> recordsByItem) {
        Set<Long> leftovers = new HashSet<>();
        for (Long snapshotId : snapshotIds) {
            for (ExecutionRecord record : recordsByItem.getOrDefault(snapshotId, List.of())) {
                if (record.getRemainingExecutionItemId() != null) {
                    leftovers.add(record.getRemainingExecutionItemId());
                }
            }
        }
        return leftovers;
    }

    private long countOf(PlanReviewResponse review, PlanReviewCategory category) {
        return review.getItems().stream().filter(i -> i.getCategory() == category).count();
    }
}
