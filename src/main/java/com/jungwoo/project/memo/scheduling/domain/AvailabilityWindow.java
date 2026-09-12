package com.jungwoo.project.memo.scheduling.domain;

import java.time.LocalDateTime;

/**
 * 이번 계획에 실제로 쓸 수 있는 후보 시간 구간 하나. 단순히 "캘린더가 비어 있다"는 뜻이 아니라
 * 출처와 신뢰도, 그렇게 판단한 이유를 함께 가진다. Timefold 계산 모델의 값이 아니라
 * AvailabilityEstimateService의 산출물이며, 화면 표시와 후보 시간 슬롯 생성에 쓰인다.
 */
public record AvailabilityWindow(
        LocalDateTime startAt,
        LocalDateTime endAt,
        AvailabilitySource source,
        AvailabilityConfidence confidence,
        String reason
) {
    public long durationMinutes() {
        return java.time.Duration.between(startAt, endAt).toMinutes();
    }
}
