package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.execution.domain.PlacementType;

import java.time.LocalTime;

/**
 * 모델이 만든 실행 후보 하나 (PROPOSAL 응답의 구조화 JSON에서 파싱된 값).
 *
 * priority/placementType은 문자열로 받아 서버가 유효성을 검증한다. 날짜는 모델 출력에 맡기지
 * 않는다 — 서버가 요청의 targetDate를 강제한다. placementType이 TIME_FIXED일 때만
 * startTime/endTime을 쓰고, 그 외에는 null이어야 한다.
 */
public record ProposalItem(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        PlacementType placementType,
        LocalTime startTime,
        LocalTime endTime
) {
}
