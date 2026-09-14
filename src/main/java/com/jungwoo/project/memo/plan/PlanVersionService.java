package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 확정된 계획의 조회. 생성(확정)은 단계 5의 확정 서비스가 담당한다.
 *
 * 이 서비스는 계획의 "항목 목록"을 돌려주지 않는다. 계획 화면이 보여줄 항목은 항상 현재
 * execution_items이고(11-period-plan.md §5-3), 스냅샷은 회고에서만 쓴다. 그 경계를 흐리지
 * 않기 위해 여기서 스냅샷을 풀어 반환하는 메서드를 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanVersionService {

    private final PlanVersionMapper planVersionMapper;
    private final PlanSnapshotCodec snapshotCodec;
    private final ExecutionItemMapper executionItemMapper;

    /**
     * 그 날짜를 포함하는 계획 목록. courseId를 주면 그 프로젝트의 항목을 하나라도 담은
     * 계획만 남긴다.
     *
     * 필터를 SQL이 아니라 자바에서 하는 이유: 조건이 JSON 배열 안의 값이라 어차피 인덱스를
     * 타지 못하고, MariaDB의 JSON 함수로 배열 원소의 속성을 비교하려면 질의가 읽기 어려워진다.
     * 한 사용자의 특정 날짜를 덮는 계획은 많아야 서너 개이므로 SQL이 좁혀준 뒤 걸러도 충분하다.
     * 정렬은 SQL이 이미 끝냈고 filter는 순서를 보존하므로 대표 계획 선택 규칙은 그대로다.
     *
     * courseId 필터가 없으면 자료구조 화면에 기간이 더 짧은 영어 계획이 대표로 뜬다.
     */
    @Transactional(readOnly = true)
    public List<PlanVersion> findCoveringDate(Long userId, LocalDate date, Long courseId) {
        List<PlanVersion> covering = planVersionMapper.findCoveringDate(userId, date);
        if (courseId == null) {
            return covering;
        }
        return covering.stream()
                .filter(plan -> snapshotCodec.containsCourse(plan.getItemsSnapshot(), courseId))
                .toList();
    }

    /**
     * 초안 요청이 강도를 주지 않았을 때 쓸 값을 정한다(11-period-plan.md §5-1-2).
     *
     * ```
     * 1. 가장 최근 확정한 plan_version의 intensity
     * 2. 그것도 없으면 NORMAL
     * ```
     *
     * 사용자 설정 컬럼도 설정 화면도 만들지 않는다. 사용자가 강도를 바꾸면 그것이 자동으로
     * 다음 기본값이 되므로, 따로 고치러 갈 곳이 없는 편이 낫다.
     *
     * 확정 이력은 있는데 그 계획에 강도가 없는 경우(강도 도입 전에 만든 계획)도 NORMAL로
     * 떨어진다 — 더 과거로 거슬러 올라가며 강도가 있는 계획을 찾지는 않는다. "직전 계획을
     * 이어받는다"는 규칙이 "언젠가 쓴 적 있는 강도를 되살린다"가 되면 예측할 수 없다.
     */
    @Transactional(readOnly = true)
    public PlanIntensity resolveIntensity(Long userId, PlanIntensity requested) {
        if (requested != null) {
            return requested;
        }
        PlanVersion latest = planVersionMapper.findLatestConfirmed(userId);
        if (latest == null || latest.getIntensity() == null) {
            return PlanIntensity.DEFAULT;
        }
        return latest.getIntensity();
    }

    /**
     * 이 계획의 현재 항목들. 계획 화면이 쓴다.
     *
     * ★ 스냅샷이 아니라 현재 execution_items를 본다(11-period-plan.md §5-3). 그리고
     * planKey로 걸러 같은 기간의 다른 계획 항목이 섞이지 않게 한다 — 날짜만으로 거르면
     * "이번 주에 뭐 하지"에 남의 계획이 끼어든다.
     *
     * 기간은 그 계획의 start_date~end_date다. 항목을 기간 밖으로 옮기면 여기서 사라지는데,
     * 그건 의도한 동작이다 — "어디 갔지"는 회고가 답한다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionItem> findItems(Long userId, Long planVersionId) {
        PlanVersion plan = getOwned(userId, planVersionId);
        return executionItemMapper.findByPlanKeyAndRange(
                userId, plan.getPlanKey(), plan.getStartDate(), plan.getEndDate());
    }

    @Transactional(readOnly = true)
    public PlanVersion getOwned(Long userId, Long planVersionId) {
        PlanVersion plan = planVersionMapper.findByIdAndUserId(planVersionId, userId);
        if (plan == null) {
            throw new NotFoundException(ErrorCode.PLAN_VERSION_NOT_FOUND);
        }
        return plan;
    }

    /**
     * 같은 plan_key의 판 목록(최신 version 우선). 지금은 항상 한 건이지만, 재계획이
     * 들어오면 여기가 그대로 이력 조회가 된다.
     */
    @Transactional(readOnly = true)
    public List<PlanVersion> findByPlanKey(Long userId, String planKey) {
        List<PlanVersion> versions = planVersionMapper.findByPlanKeyAndUserId(planKey, userId);
        if (versions.isEmpty()) {
            throw new NotFoundException(ErrorCode.PLAN_VERSION_NOT_FOUND);
        }
        return versions;
    }
}
