package com.jungwoo.project.memo.scheduling.domain;

import java.time.LocalDateTime;

/**
 * SchedulingTask의 planning variable 값 하나 — "이 후보를 이 시각에 시작할 수 있다"는 뜻이다.
 * AvailabilityEstimateService가 실제 가용시간 구간 안에서만 후보를 생성하므로, 이 값으로
 * 배치된 결과는 항상 "가용시간 안"이라는 조건을 값 생성 단계에서부터 만족한다. confidence는
 * 소프트 제약(신뢰도가 높은 시간을 먼저 쓴다)에서만 쓰인다.
 *
 * equals/hashCode를 startAt 기준으로 정의한다 — 같은 시각이면 confidence가 달라도 Timefold
 * 입장에서는 같은 배치로 취급해야 이동 연산(변경 이웃 탐색)이 정상적으로 동작한다.
 */
public final class TimeSlotOption {

    private final LocalDateTime startAt;
    private final AvailabilityConfidence confidence;

    public TimeSlotOption(LocalDateTime startAt, AvailabilityConfidence confidence) {
        this.startAt = startAt;
        this.confidence = confidence;
    }

    public LocalDateTime startAt() {
        return startAt;
    }

    public AvailabilityConfidence confidence() {
        return confidence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimeSlotOption other)) return false;
        return startAt.equals(other.startAt);
    }

    @Override
    public int hashCode() {
        return startAt.hashCode();
    }

    @Override
    public String toString() {
        return "TimeSlotOption[" + startAt + ", " + confidence + "]";
    }
}
