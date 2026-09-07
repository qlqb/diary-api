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
 * <p><b>lead 발생분.</b> 루틴에 이동시간(leadMinutes &gt; 0)이 있으면 원 발생분 앞에
 * {@code lead=true}인 발생분이 하나 더 나온다(제목 "{루틴이름} 이동"). 시간만 막는 값이다 —
 * 배치 대상도 기록 대상도 아니므로, 전개 결과로 레코드를 만드는 호출처는 이 플래그로 걸러야
 * 한다. 가용시간 계산과 화면 표시는 그대로 포함한다.
 *
 * @param routineId  원 루틴. lead 발생분도 같은 값이다 — 화면이 원 루틴으로 이동하는 데 쓴다
 * @param sourceDate 규칙상 원래 날짜. MOVED면 이동 전 날짜다. startAt의 날짜와 다를 수 있다
 * @param moved      이동해 온 발생분인가. 화면이 "보강" 표시를 붙이는 데 쓴다
 * @param lead       이동시간 발생분인가. 원 발생분 바로 앞 구간이며 편집·배치·기록 대상이 아니다
 */
public record RoutineOccurrence(
        Long routineId,
        Long courseId,
        String title,
        String location,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDate sourceDate,
        boolean moved,
        boolean lead
) {

    /** 원 발생분. lead 플래그가 생기기 전의 모양을 그대로 받는다. */
    public RoutineOccurrence(Long routineId, Long courseId, String title, String location,
                             LocalDateTime startAt, LocalDateTime endAt, LocalDate sourceDate,
                             boolean moved) {
        this(routineId, courseId, title, location, startAt, endAt, sourceDate, moved, false);
    }

    /** 이 발생분 앞의 이동시간 발생분. 호출부가 시작 시각(자정 clip 뒤)을 정한다. */
    public RoutineOccurrence leadFrom(LocalDateTime leadStart) {
        return new RoutineOccurrence(routineId, courseId, title + " 이동", location,
                leadStart, startAt, sourceDate, moved, true);
    }
}
