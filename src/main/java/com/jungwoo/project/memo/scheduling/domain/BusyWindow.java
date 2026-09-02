package com.jungwoo.project.memo.scheduling.domain;

import java.time.LocalDateTime;

/**
 * Timefold 계산 모델의 문제 사실(problem fact). 이미 다른 무언가로 막혀 있어 새 후보를
 * 배치할 수 없는 구간이다 — 기존 TIME_FIXED 실행 조각, AI/사용자가 명시한 사용 불가 시간,
 * 사용자가 고정한 다른 후보 항목이 모두 여기로 합쳐져 들어온다.
 *
 * DB 엔티티(ExecutionItem)를 그대로 재사용하지 않고 계산에 필요한 최소 필드만 가진 별도
 * 모델이다.
 */
public record BusyWindow(
        LocalDateTime startAt,
        LocalDateTime endAt,
        String label
) {
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return startAt.isBefore(otherEnd) && otherStart.isBefore(endAt);
    }
}
