package com.jungwoo.project.memo.routine.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 특정 날짜에 실제로 도는 반복 일정 한 건.
 *
 * <p>테이블이 없다. 행을 미리 만들면 "몇 개를 미리 만들 것인가"에 답이 없다 — 무기한 루틴은
 * 끝이 없다. 조회할 때마다 규칙에서 계산해 응답에만 담고, 화면은 응답을 그리므로 행 없이도
 * 보인다. v1에 필요한 것은 "피하기"와 "보이기"뿐이고 둘 다 이걸로 된다.
 *
 * @param sourceDate 규칙상 원래 날짜. MOVED면 이동 전 날짜다. startAt의 날짜와 다를 수 있다
 * @param moved      이동해 온 발생분인가. 화면이 "보강" 표시를 붙이는 데 쓴다
 */
public record RoutineOccurrence(
        Long routineId,
        Long courseId,
        String title,
        String location,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDate sourceDate,
        boolean moved
) {
}
