package com.jungwoo.project.memo.ai.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 대화에서 사용자가 명시한 사용 불가 시간 하나(예: "화요일 저녁은 알바").
 * date/dayOfWeek 중 정확히 하나만 값을 가진다 — dayOfWeek는 계획 범위 안에서 매주 반복
 * 적용되고, date는 특정 하루에만 적용되는 예외다. 서버는 이 값을 AI_INFERRED로만 취급하고
 * HARD 제약으로 승격하지 않는다(가용시간 추정의 입력일 뿐, 사용자 확정 사실로 별도 저장하지
 * 않는다).
 */
public record UnavailableWindowSpec(
        LocalDate date,
        java.time.DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        String reason
) {
}
