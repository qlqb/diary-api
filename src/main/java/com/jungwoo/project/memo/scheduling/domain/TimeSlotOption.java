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
// 주의: 같은 시각을 후보로 가진 태스크가 둘 이상이면, 배정된 인스턴스의
// confidence가 자기 목록의 값이 아닐 수 있다. startAt은 항상 맞으므로
// 하드 제약과 순서 제약은 영향이 없고, preferHighConfidenceSlot의 점수만
// 어긋난다. 고치려면 equals에 confidence를 넣어야 하는데 그러면 이동 연산이
// 달라진다. (2026-08-30 확인, 실사용에서 문제가 되면 그때 판단)
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
