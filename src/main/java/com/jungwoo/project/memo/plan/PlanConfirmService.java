package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 계획 확정. 제안을 실제 execution_items와 plan_versions로 바꾸는 유일한 경로다.
 *
 * ★ 한 트랜잭션이다. 5개 중 3개만 생기고 실패하면 스냅샷은 5개라는데 실제로는 3개가 되고,
 * 회고에서 그 2개가 "계획했는데 배치 안 함"으로 잘못 읽힌다. 첫날부터 거짓 회고가 생긴다.
 *
 * ★ 확정 시점에 솔버를 돌리지 않는다. 시각 배치는 롤링 배치(PlanPlacementService)가 전담한다 —
 * 7일 이하 계획도 마찬가지다. 배치 경로를 둘로 두면 "왜 이 계획은 시각이 있고 저건 없나"를
 * 기간 길이로 설명해야 하고, 그 규칙이 화면·회고·재배치 전부에 번진다.
 *
 * <p>★ 재계획(2026-09-17). 초안에는 새 항목(CREATE)만이 아니라 기존 계획 항목에 대한 결정 — 줄임(REDUCE)·이동(MOVE)·
 * 보류(DROP)·유지(KEEP) — 가 함께 온다. 그때 확정은 <b>같은 plan_key의 다음 판</b>이다. 기존 항목의 생성 출처
 * ({@code plan_version_id})는 처음 만든 판 그대로이고 새 항목만 새 판을 출처로 갖는다. 새 판의 스냅샷은 "계속할
 * 범위"다: 유지·조정된 기존 항목 + 새 항목. 보류한 항목은 스냅샷에서 빠지지만 지워지지 않는다(DROP = HOLD, 삭제가
 * 아니다). 출처(누가 만들었나)와 소속(지금 어느 계획에 들어 있나)은 다른 질문이고, 소속은 plan_key + 기간으로 본다
 * ({@link PlanVersionService#findItems}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanConfirmService {

    private static final int MAX_PLAN_DAYS = 31;
    static final String CONTINUED_REASON = "이전 판에서 이어짐";

    private final AiProposalService aiProposalService;
    private final AiProposalMapper aiProposalMapper;
    private final PlanVersionMapper planVersionMapper;
    private final PlanSnapshotCodec snapshotCodec;
    private final PlanStrategyCodec strategyCodec;
    private final ExecutionItemMapper executionItemMapper;
    private final CourseMapper courseMapper;
    private final PlanConfirmScheduleGuard scheduleGuard;

    @Transactional
    public PlanVersion confirm(Long userId, Long proposalId, PlanConfirmRequest request) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        // 기간·강도·목표는 전부 제안에서 읽는다. 클라이언트가 다시 보내면 초안과 다른 값으로
        // 확정될 수 있고, 그러면 스냅샷의 기간과 항목의 planning_* 가 어긋난다.
        if (proposal.getPlanStartDate() == null || proposal.getPlanEndDate() == null) {
            // 계획 경로로 만들어진 제안이 아니다.
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int days = (int) ChronoUnit.DAYS.between(proposal.getPlanStartDate(), proposal.getPlanEndDate()) + 1;
        if (days < 1 || days > MAX_PLAN_DAYS) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 0. 미리보기가 정한 시각이 지금의 일정과 겹치는지 최신 커밋 기준으로 본다. 미리보기와
        //    확정 사이에 근무가 생겼으면 여기서 전체를 거절한다 — 겹친 항목만 빼고 확정하면
        //    사용자가 승인한 계획과 저장된 계획이 조용히 달라진다.
        scheduleGuard.ensureNoConflicts(userId, proposal.getPlanStartDate(), proposal.getPlanEndDate(),
                request.getEditedItems());

        // 1. 제안 적용 → 새 항목은 execution_items 생성, 조정 항목은 기존 항목의 도메인 액션(줄임·이동·보류).
        //    승인 전 미반영 원칙은 여기서 이미 보장되고, base_version이 낡았으면 409로 전체가 되돌아간다.
        AiProposalResponse applied = aiProposalService.apply(proposalId, userId,
                AiProposalApplyRequest.builder()
                        .editedItems(request.getEditedItems())
                        .excludedItemIds(request.getExcludedItemIds())
                        .build());

        List<Long> createdIds = new ArrayList<>();
        Map<Long, LocalDateTime> deadlines = new HashMap<>();
        Set<Long> adjustedIds = new LinkedHashSet<>();
        Set<Long> heldIds = new LinkedHashSet<>();
        Map<Long, String> continuingReasons = new HashMap<>();
        for (AiProposalItemResponse item : applied.getItems()) {
            if (item.getStatus() == AiProposalItemStatus.DISMISSED) {
                continue; // 사용자가 이번 확정에서 뺀 제안
            }
            ProposalOperation operation = item.getOperation() == null ? ProposalOperation.CREATE : item.getOperation();
            if (operation != ProposalOperation.CREATE) {
                // 조정 항목: created_item_id에는 "바뀐 대상"이 남는다. 새 항목이 아니므로 출처를 새로 찍지 않는다.
                Long target = item.getTargetExecutionItemId() != null ? item.getTargetExecutionItemId() : item.getCreatedItemId();
                if (target == null) {
                    continue;
                }
                adjustedIds.add(target);
                if (operation == ProposalOperation.DROP) {
                    heldIds.add(target);
                }
                continuingReasons.put(target, decisionReason(operation, item.getReason()));
                continue;
            }
            // created_item_id를 그대로 쓴다 — 추측하지 않는다.
            if (item.getCreatedItemId() == null) {
                continue;
            }
            createdIds.add(item.getCreatedItemId());
            LocalDateTime deadline = resolveDeadline(item);
            if (deadline != null) {
                deadlines.put(item.getCreatedItemId(), deadline);
            }
        }
        if (createdIds.isEmpty() && adjustedIds.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
        }

        // 2. 이 초안이 다루는 기존 계획. 조정 대상과 전략의 기존 항목 결정(KEEP 포함)이 가리키는 항목의 출처 판에서
        //    plan_key를 찾는다. 없으면 새 계획(새 UUID, 1판)이다.
        PlanStrategy strategy = strategyCodec.fromJson(proposal.getPlanStrategyJson());
        Set<Long> continuingIds = new LinkedHashSet<>(adjustedIds);
        if (strategy != null && strategy.existingDecisions() != null) {
            for (PlanStrategy.ExistingDecision decision : strategy.existingDecisions()) {
                if (decision == null || decision.executionItemId() == null) {
                    continue;
                }
                continuingIds.add(decision.executionItemId());
                continuingReasons.putIfAbsent(decision.executionItemId(),
                        decisionReason(decision.action(), decision.reason()));
            }
        }
        PlanVersion base = resolveBasePlan(userId, continuingIds);

        // 3. 마감 시각을 옮긴다. 제안 payload에만 있던 값이 execution_items로 넘어오는
        //    유일한 지점이고, 여기서부터 롤링 배치가 Timefold에 HARD 제약으로 전달한다.
        //    조각을 만든 것도 이 트랜잭션이므로 대상 행이 없을 수 없다 — 0행이면 조각과
        //    마감이 어긋난 채 커밋되는 것이므로 전체를 되돌린다.
        for (Map.Entry<Long, LocalDateTime> entry : deadlines.entrySet()) {
            int updated = executionItemMapper.assignDeadlineAt(userId, entry.getKey(), entry.getValue());
            if (updated != 1) {
                throw new IllegalStateException(
                        "마감 시각 기록 행 수 불일치: executionItemId=" + entry.getKey() + ", 실제=" + updated);
            }
        }

        // 4. 스냅샷 = 계속할 범위. 기존 계획의 항목(이 기간, 보류·취소 제외) + 새 항목(미배치는 계획 기간을 채운다).
        Map<Long, ExecutionItem> snapshotSource = new LinkedHashMap<>();
        if (base != null) {
            for (ExecutionItem item : executionItemMapper.findByPlanKeyAndRange(userId, base.getPlanKey(),
                    proposal.getPlanStartDate(), proposal.getPlanEndDate())) {
                if (heldIds.contains(item.getExecutionItemId())
                        || item.getStatus() == ExecutionStatus.HOLD || item.getStatus() == ExecutionStatus.CANCELLED) {
                    continue;
                }
                snapshotSource.put(item.getExecutionItemId(), item);
            }
        }
        List<ExecutionItem> created = createdIds.isEmpty() ? List.of()
                : executionItemMapper.findByIdsForReview(userId, createdIds);
        for (ExecutionItem item : created) {
            if (item.getPlacementType() == PlacementType.UNSCHEDULED) {
                executionItemMapper.assignPlanningRange(userId, item.getExecutionItemId(),
                        proposal.getPlanStartDate(), proposal.getPlanEndDate());
                item.setPlanningStartDate(proposal.getPlanStartDate());
                item.setPlanningEndDate(proposal.getPlanEndDate());
            }
            snapshotSource.put(item.getExecutionItemId(), item);
        }
        Map<Long, String> courseTitles = courseTitles(userId, new ArrayList<>(snapshotSource.values()));
        List<PlanSnapshotItem> snapshotItems = new ArrayList<>();
        for (ExecutionItem item : snapshotSource.values()) {
            // courseId가 null인 항목("기타" 그룹)도 있으므로 null 키로 조회하지 않는다.
            String courseTitle = item.getCourseId() != null ? courseTitles.get(item.getCourseId()) : null;
            boolean isNew = createdIds.contains(item.getExecutionItemId());
            String reason = isNew ? item.getDescription()
                    : continuingReasons.getOrDefault(item.getExecutionItemId(), CONTINUED_REASON);
            snapshotItems.add(snapshotCodec.toSnapshotItem(item, courseTitle, reason));
        }

        // 5. plan_versions INSERT. 기존 계획을 이어 가면 같은 plan_key의 다음 판, 아니면 새 UUID의 1판.
        //    같은 계획을 두 트랜잭션이 동시에 이어 가면 (plan_key, version) UNIQUE가 한쪽을 막는다.
        PlanVersion planVersion = PlanVersion.builder()
                .userId(userId)
                .planKey(base != null ? base.getPlanKey() : UUID.randomUUID().toString())
                .version(base != null ? base.getVersion() + 1 : 1)
                .startDate(proposal.getPlanStartDate())
                .endDate(proposal.getPlanEndDate())
                .title(request.getTitle() != null && !request.getTitle().isBlank()
                        ? request.getTitle() : defaultTitle(proposal))
                .goalSummary(request.getGoalSummary())
                .intensity(proposal.getPlanIntensity())
                .targetMinutes(proposal.getPlanTargetMinutes())
                .itemsSnapshot(snapshotCodec.toJson(snapshotItems))
                // 초안 시점의 판단을 그대로 옮긴다. 확정 요청은 전략을 받지 않는다 —
                // 기간·강도와 같은 이유로, 클라이언트가 다시 보내면 사용자가 승인한 판단과
                // 저장되는 판단이 달라질 수 있다.
                .strategyJson(proposal.getPlanStrategyJson())
                // 출처 스냅샷도 같은 이유로 옮긴다. 확정 요청은 이 값을 받지 않는다 —
                // 클라이언트가 다시 보내면 사용자가 본 근거와 저장되는 근거가 달라진다.
                .provenanceJson(proposal.getPlanProvenanceJson())
                .sourceProposalId(proposalId)
                .build();
        planVersionMapper.insert(planVersion);

        // 6. 생성 출처를 한 번만 기록한다 — 새 항목에만. plan_version_id IS NULL 조건이 그 강제이고, 조정·유지한
        //    기존 항목은 여기 넣지 않는다(그것들의 출처는 처음 만든 판이다).
        if (!createdIds.isEmpty()) {
            int assigned = executionItemMapper.assignPlanVersionId(userId, createdIds, planVersion.getPlanVersionId());
            if (assigned != createdIds.size()) {
                // 이미 출처가 박힌 조각이 섞였다는 뜻이다. 스냅샷과 실제가 어긋난 상태로
                // 커밋하는 것보다 전체를 되돌리는 편이 낫다.
                throw new IllegalStateException(
                        "plan_version_id 기록 행 수 불일치: 기대=" + createdIds.size() + ", 실제=" + assigned);
            }
        }

        log.info("계획 확정: userId={}, planVersionId={}, planKey={} v{}, proposalId={}, {}~{}, 새 항목={}개, 조정={}개(보류 {}개), "
                        + "스냅샷={}개, intensity={}, target={}분",
                userId, planVersion.getPlanVersionId(), planVersion.getPlanKey(), planVersion.getVersion(), proposalId,
                planVersion.getStartDate(), planVersion.getEndDate(), createdIds.size(), adjustedIds.size(), heldIds.size(),
                snapshotItems.size(), planVersion.getIntensity(), planVersion.getTargetMinutes());
        return planVersion;
    }

    /**
     * 이 초안이 이어 가는 기존 계획. 가리킨 기존 항목들의 출처 판을 plan_key로 묶어 가장 많은 항목이 속한 계획의
     * <b>최신 판</b>을 돌려준다. 어느 항목도 계획 출처가 없으면(수동 항목만 조정) null — 새 계획이다.
     */
    private PlanVersion resolveBasePlan(Long userId, Set<Long> continuingIds) {
        if (continuingIds.isEmpty()) {
            return null;
        }
        Map<Long, PlanVersion> versionsById = new HashMap<>();
        Map<String, Integer> countByKey = new LinkedHashMap<>();
        for (ExecutionItem item : executionItemMapper.findByIdsForReview(userId, new ArrayList<>(continuingIds))) {
            if (item.getPlanVersionId() == null) {
                continue;
            }
            PlanVersion origin = versionsById.computeIfAbsent(item.getPlanVersionId(),
                    id -> planVersionMapper.findByIdAndUserId(id, userId));
            if (origin != null) {
                countByKey.merge(origin.getPlanKey(), 1, Integer::sum);
            }
        }
        if (countByKey.isEmpty()) {
            return null;
        }
        String planKey = countByKey.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        if (countByKey.size() > 1) {
            log.info("재계획 확정: 기존 항목이 여러 계획({})에 걸쳐 있어 가장 많은 항목이 속한 {}을 잇는다", countByKey.keySet(), planKey);
        }
        List<PlanVersion> versions = planVersionMapper.findByPlanKeyAndUserId(planKey, userId);
        return versions.isEmpty() ? null : versions.get(0); // version DESC
    }

    private static String decisionReason(Object action, String reason) {
        String label = switch (String.valueOf(action)) {
            case "REDUCE" -> "줄임";
            case "MOVE" -> "옮김";
            case "DROP" -> "보류";
            default -> "유지";
        };
        return reason == null || reason.isBlank() ? label : label + ": " + reason;
    }

    /**
     * 이 항목이 execution_items에 남길 마감 시각.
     *
     * deadlineAt이 있으면 그대로 쓴다 — 근거("다음 수업 시작")가 있을 때만 채워지는 시각이다.
     * 없고 deadlineDate만 있으면 다음날 00:00으로 바꾼다. "9/9까지"는 9/9 안에 끝내면
     * 된다는 뜻이므로 경계는 9/10 00:00이고, 미리보기(SchedulePreviewService)가 쓰는 변환과
     * 같은 규칙이다. 이 변환 덕분에 예전부터 있던 날짜 마감도 확정 이후까지 살아남는다.
     */
    private LocalDateTime resolveDeadline(AiProposalItemResponse item) {
        if (item.getDeadlineAt() != null) {
            return item.getDeadlineAt();
        }
        if (item.getDeadlineDate() != null) {
            return item.getDeadlineDate().plusDays(1).atStartOfDay();
        }
        return null;
    }

    private Map<Long, String> courseTitles(Long userId, List<ExecutionItem> items) {
        List<Long> courseIds = items.stream()
                .map(ExecutionItem::getCourseId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> titles = new HashMap<>();
        if (courseIds.isEmpty()) {
            return titles;
        }
        for (Course course : courseMapper.findByIdsAndUserId(courseIds, userId)) {
            titles.put(course.getCourseId(), course.getTitle());
        }
        return titles;
    }

    private String defaultTitle(AiProposal proposal) {
        return proposal.getPlanStartDate().getMonthValue() + "월 "
                + proposal.getPlanStartDate().getDayOfMonth() + "일 계획";
    }
}
