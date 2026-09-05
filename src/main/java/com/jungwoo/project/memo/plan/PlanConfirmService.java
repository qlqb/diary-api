package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
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
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
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
import java.util.List;
import java.util.Map;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanConfirmService {

    private static final int MAX_PLAN_DAYS = 31;

    private final AiProposalService aiProposalService;
    private final AiProposalMapper aiProposalMapper;
    private final PlanVersionMapper planVersionMapper;
    private final PlanSnapshotCodec snapshotCodec;
    private final ExecutionItemMapper executionItemMapper;
    private final CourseMapper courseMapper;

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

        // 1. 제안 적용 → execution_items 생성. 승인 전 미반영 원칙은 여기서 이미 보장된다.
        AiProposalResponse applied = aiProposalService.apply(proposalId, userId,
                AiProposalApplyRequest.builder()
                        .editedItems(request.getEditedItems())
                        .excludedItemIds(request.getExcludedItemIds())
                        .build());

        List<Long> createdIds = new ArrayList<>();
        Map<Long, LocalDateTime> deadlines = new HashMap<>();
        for (AiProposalItemResponse item : applied.getItems()) {
            // created_item_id를 그대로 쓴다 — 추측하지 않는다. 조정 항목은 이 컬럼에 "바뀐
            // 대상"을 남기지만 계획 경로의 제안에는 조정 항목이 없다.
            if (item.getCreatedItemId() == null) {
                continue;
            }
            createdIds.add(item.getCreatedItemId());
            LocalDateTime deadline = resolveDeadline(item);
            if (deadline != null) {
                deadlines.put(item.getCreatedItemId(), deadline);
            }
        }
        if (createdIds.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
        }

        // 2. 마감 시각을 옮긴다. 제안 payload에만 있던 값이 execution_items로 넘어오는
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

        // 3. 미배치 항목에 계획 기간을 채우고 스냅샷을 조립한다.
        List<ExecutionItem> created = executionItemMapper.findByIdsForReview(userId, createdIds);
        Map<Long, String> courseTitles = courseTitles(userId, created);
        List<PlanSnapshotItem> snapshotItems = new ArrayList<>();
        for (ExecutionItem item : created) {
            if (item.getPlacementType() == PlacementType.UNSCHEDULED) {
                executionItemMapper.assignPlanningRange(userId, item.getExecutionItemId(),
                        proposal.getPlanStartDate(), proposal.getPlanEndDate());
                item.setPlanningStartDate(proposal.getPlanStartDate());
                item.setPlanningEndDate(proposal.getPlanEndDate());
            }
            // courseId가 null인 항목("기타" 그룹)도 있으므로 null 키로 조회하지 않는다.
            String courseTitle = item.getCourseId() != null ? courseTitles.get(item.getCourseId()) : null;
            snapshotItems.add(snapshotCodec.toSnapshotItem(item, courseTitle, item.getDescription()));
        }

        // 4. plan_versions INSERT. plan_key는 항상 새 UUID이고 version은 1이다 — 재계획은
        //    1차 범위 밖이라 MAX(version)+1 경합이 발생하지 않는다.
        PlanVersion planVersion = PlanVersion.builder()
                .userId(userId)
                .planKey(UUID.randomUUID().toString())
                .version(1)
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
                .sourceProposalId(proposalId)
                .build();
        planVersionMapper.insert(planVersion);

        // 5. 생성 출처를 한 번만 기록한다. plan_version_id IS NULL 조건이 그 강제다.
        int assigned = executionItemMapper.assignPlanVersionId(
                userId, createdIds, planVersion.getPlanVersionId());
        if (assigned != createdIds.size()) {
            // 이미 출처가 박힌 조각이 섞였다는 뜻이다. 스냅샷과 실제가 어긋난 상태로
            // 커밋하는 것보다 전체를 되돌리는 편이 낫다.
            throw new IllegalStateException(
                    "plan_version_id 기록 행 수 불일치: 기대=" + createdIds.size() + ", 실제=" + assigned);
        }

        log.info("계획 확정: userId={}, planVersionId={}, proposalId={}, {}~{}, 항목={}개, intensity={}, target={}분",
                userId, planVersion.getPlanVersionId(), proposalId,
                planVersion.getStartDate(), planVersion.getEndDate(), createdIds.size(),
                planVersion.getIntensity(), planVersion.getTargetMinutes());
        return planVersion;
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
