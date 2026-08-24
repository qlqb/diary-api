package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;

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
        Long courseId
) {
}
