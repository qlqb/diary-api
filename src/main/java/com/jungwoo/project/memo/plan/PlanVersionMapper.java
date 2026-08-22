package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.domain.PlanVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * plan_versions 접근.
 *
 * ★ update와 delete를 만들지 않는다. 확정된 계획은 불변이고, 그 불변성을 주석이 아니라
 * "고칠 메서드가 존재하지 않는다"는 사실로 강제한다. 계획을 바꾸고 싶으면 execution_items를
 * 바꾸는 것이지 스냅샷을 고치는 것이 아니다. 재계획이 필요해지면 새 version을 INSERT한다.
 *
 * 이 규칙을 깨려는 변경(예: "제목 오타만 고치게 해달라")이 들어오면, 스냅샷을 고치는 대신
 * 왜 그 요구가 생겼는지를 먼저 본다 — 대개 계획 화면이 스냅샷을 보여주고 있다는 신호다.
 */
@Mapper
public interface PlanVersionMapper {

    void insert(PlanVersion planVersion);

    PlanVersion findByIdAndUserId(
            @Param("planVersionId") Long planVersionId,
            @Param("userId") Long userId
    );

    /**
     * 그 날짜를 포함하는 계획 목록. 같은 날짜에 8월 계획·이번 주 계획·오늘 계획이 동시에
     * 걸릴 수 있으므로 단건이 아니라 목록을 반환한다.
     *
     * 정렬은 기간이 짧은 순, 같으면 최근 확정 순이다. 화면은 첫 번째를 대표로 쓰고
     * 재정렬하지 않는다 — 어느 계획을 강조할지의 규칙을 서버 한 곳에만 둔다.
     */
    List<PlanVersion> findCoveringDate(
            @Param("userId") Long userId,
            @Param("date") LocalDate date
    );

    /** 같은 plan_key의 모든 판. 지금은 항상 한 건이다(version = 1 고정). */
    List<PlanVersion> findByPlanKeyAndUserId(
            @Param("planKey") String planKey,
            @Param("userId") Long userId
    );
}
