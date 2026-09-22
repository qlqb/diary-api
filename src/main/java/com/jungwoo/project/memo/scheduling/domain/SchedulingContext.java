package com.jungwoo.project.memo.scheduling.domain;

import java.time.LocalDateTime;

/**
 * 제약 스트림에서 "지금"과 "계획 범위"를 참조하기 위한 단일 문제 사실(problem fact).
 * candidateStarts 생성 단계에서 이미 과거·범위 밖을 걸러내지만, 제약에서도 같은 조건을
 * 명시적으로 검사해 이중으로 방어한다(계획 항목 요구사항 9).
 */
public record SchedulingContext(
        LocalDateTime now,
        LocalDateTime horizonStart,
        LocalDateTime horizonEnd
) {
}
