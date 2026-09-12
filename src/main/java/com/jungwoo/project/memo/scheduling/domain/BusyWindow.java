package com.jungwoo.project.memo.scheduling.domain;

import java.time.LocalDateTime;

/**
 * Timefold 계산 모델의 문제 사실(problem fact). 이미 다른 무언가로 막혀 있어 새 후보를
 * 배치할 수 없는 구간이다 — 기존 TIME_FIXED 실행 조각, AI/사용자가 명시한 사용 불가 시간,
 * 사용자가 고정한 다른 후보 항목이 모두 여기로 합쳐져 들어온다.
 *
 * DB 엔티티(ExecutionItem)를 그대로 재사용하지 않고 계산에 필요한 최소 필드만 가진 별도
 * 모델이다.
 *
 * <p>source/sourceId는 계산에 쓰지 않는다. 계획 생성이 "이 남는 시간 추정은 어떤 일정들을
 * 빼고 나온 값인가"를 원본 행까지 가리키기 위해 붙는 추적 정보다. 라벨은 사람이 읽는
 * 문자열이라 같은 이름이 여럿일 수 있어 원본을 특정하지 못한다.
 */
public record BusyWindow(
        LocalDateTime startAt,
        LocalDateTime endAt,
        String label,
        BusySource source,
        Long sourceId
) {

    /** 출처를 모르는 자리에서 만드는 기존 경로(테스트 픽스처 등). */
    public BusyWindow(LocalDateTime startAt, LocalDateTime endAt, String label) {
        this(startAt, endAt, label, null, null);
    }

    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return startAt.isBefore(otherEnd) && otherStart.isBefore(endAt);
    }
}
