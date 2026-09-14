package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.DoneCriteriaSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 모델이 만든 실행 후보 하나 (PROPOSAL 응답의 구조화 JSON에서 파싱된 값).
 *
 * priority/placementType은 문자열로 받아 서버가 유효성을 검증한다. 날짜는 모델 출력에 맡기지
 * 않는다 — 서버가 요청의 targetDate를 강제한다. placementType이 TIME_FIXED일 때만
 * startTime/endTime을 쓰고, 그 외에는 null이어야 한다.
 *
 * placementType이 UNSCHEDULED이면 이 후보는 특정 하루에 묶이지 않은 "7일 범위 배치 후보"다
 * — Timefold가 날짜와 시각을 함께 고른다. earliestStartDate/deadlineDate는 그 배치를
 * 제한하는 선택적 힌트이고, fixedStartAt/fixedEndAt은 사용자가 명확한 날짜+시각을 말했을
 * 때만 채워지는 값으로 해당 후보를 그 시각에 그대로 고정한다(Timefold가 움직이지 않는다).
 * 서버는 이 값들도 최종적으로 검증·클램프하며 모델 출력을 그대로 신뢰하지 않는다.
 *
 * deadlineAt은 deadlineDate보다 강한 값이다 — 날짜가 아니라 시각이고, 근거("다음 수업
 * 시작")가 있을 때만 채워지며 확정 이후 execution_items까지 살아남는다. 둘 다 있으면
 * deadlineAt이 이긴다.
 */
public record ProposalItem(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        PlacementType placementType,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate earliestStartDate,
        LocalDate deadlineDate,
        LocalDateTime fixedStartAt,
        LocalDateTime fixedEndAt,
        /**
         * 이 후보가 속한 프로젝트. 기간 계획처럼 여러 프로젝트를 한 제안에 담는 경로에서만
         * 값이 있다. null이면 기존처럼 제안이 달린 대화의 프로젝트를 따른다.
         */
        Long courseId,

        /**
         * 근거 있는 마감 시각. 다음 수업 시작·시험·과제 마감·사용자 명시처럼 실제 사건이
         * 있을 때만 채운다. deadlineDate와 달리 확정 이후에도 execution_items.deadline_at으로
         * 남아 롤링 배치의 HARD 제약이 된다.
         */
        LocalDateTime deadlineAt,

        /**
         * 이 후보가 다루는 학습 항목. 확정 시 execution_items.topic_id로 남는다.
         *
         * <p>이 값이 있어야 나중에 「이미 알아요」와 진행 상태가 이 조각을 되짚을 수 있다.
         * 없으면 조각과 학습 항목의 연결이 제목 문자열뿐이고, 제목은 사용자가 고칠 수 있다.
         */
        Long topicId,

        /**
         * 계획 경로가 채우는 학습 정보. 다른 제안 경로에는 채울 근거가 없어 전부 null이다.
         *
         * <p>doneCriteria와 sourceLocator는 description에도 합쳐져 저장되지만(스냅샷 보존),
         * 화면이 그 문자열을 다시 쪼개게 두지 않으려고 따로 싣는다 — 표시 형식이 바뀔 때마다
         * 파싱이 깨지는 계약은 계약이 아니다.
         */
        ActionType actionType,
        String doneCriteria,
        DoneCriteriaSource doneCriteriaSource,
        String sourceLocator
) {

    /**
     * deadlineAt·topicId 없이 만드는 기존 경로. 필드를 뒤에 붙이고 이 생성자를 남긴 것은
     * 호출부 스무 곳을 건드리지 않기 위해서다 — 둘 다 계획 경로만 채우고 나머지 경로에는
     * 채울 근거가 없다.
     */
    public ProposalItem(
            String title, String description, Integer expectedMinutes, String priority,
            PlacementType placementType, LocalTime startTime, LocalTime endTime,
            LocalDate earliestStartDate, LocalDate deadlineDate,
            LocalDateTime fixedStartAt, LocalDateTime fixedEndAt, Long courseId
    ) {
        this(title, description, expectedMinutes, priority, placementType, startTime, endTime,
                earliestStartDate, deadlineDate, fixedStartAt, fixedEndAt, courseId, null, null,
                null, null, null, null);
    }

    /** 마감까지만 아는 경로(v0 블록 생성기). */
    public ProposalItem(
            String title, String description, Integer expectedMinutes, String priority,
            PlacementType placementType, LocalTime startTime, LocalTime endTime,
            LocalDate earliestStartDate, LocalDate deadlineDate,
            LocalDateTime fixedStartAt, LocalDateTime fixedEndAt, Long courseId, LocalDateTime deadlineAt
    ) {
        this(title, description, expectedMinutes, priority, placementType, startTime, endTime,
                earliestStartDate, deadlineDate, fixedStartAt, fixedEndAt, courseId, deadlineAt, null,
                null, null, null, null);
    }
}
