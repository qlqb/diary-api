package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
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
