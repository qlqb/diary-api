package com.jungwoo.project.memo.execution.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 실행 조각의 "길이"를 누가 정하는지에 대한 규칙.
 *
 * <p>TIME_FIXED는 시작·종료 시각이 진실이고 expectedMinutes는 그 파생값이다. 17:00~23:00이
 * 확정이면 소요 시간은 360분이지 다른 값일 수 없다. 그래서 TIME_FIXED에서는 요청이 보낸
 * expectedMinutes를 입력으로 취급하지 않고 여기서 계산해 덮어쓴다.
 *
 * <p>이 규칙이 지켜지지 않아 실제로 사고가 있었다. AI 제안 검증이 expectedMinutes에 5~120분
 * 상한을 걸고 있었는데(그 상한은 "한 번에 앉아서 할 만한 단위"라는 학습 항목의 정의에서 나온
 * 값이다), 알바처럼 시각이 박힌 일정에는 그 정의가 적용되지 않는다. 모델은 검증을 통과하려고
 * 시각은 진짜로 넣고 expectedMinutes만 120으로 깎았고, 17:00~23:00짜리 알바가 120분으로
 * 저장됐다. 화면 표시가 어긋났다.
 *
 * <p>그때는 AI 경로만 고쳤는데 create/move/reduce가 같은 불일치를 다시 만들 수 있었다. 그래서
 * 계산을 각 호출부에 흩어 두지 않고 이 클래스 하나로 모은다 — 어느 경로를 타든 같은 규칙이다.
 *
 * <p>UNSCHEDULED/DATE_ONLY는 다르다. 그쪽은 아직 시각이 없어서 길이를 사람이 정하는 값이
 * 맞고, 솔버가 그 길이를 보고 배치할 슬롯을 고른다. 여기서 손대지 않는다.
 */
public final class PlacementDuration {

    private PlacementDuration() {
    }

    /**
     * 두 시각 사이의 분. execution_items의 chk_execution_items_placement가
     * scheduled_start_at &lt; scheduled_end_at을 보장하므로 결과는 항상 1 이상이다.
     */
    public static int minutesBetween(LocalDateTime startAt, LocalDateTime endAt) {
        return (int) Duration.between(startAt, endAt).toMinutes();
    }

    /**
     * 저장할 expectedMinutes를 정한다. TIME_FIXED면 시각에서 계산하고, 그 외에는 요청값을
     * 그대로 쓴다.
     *
     * <p>TIME_FIXED인데 시각이 없는 경우는 계산하지 않고 요청값을 돌려준다 — 그 조합은
     * validatePlacement가 거부할 상태이므로, 여기서 NPE로 먼저 터뜨려 원래 오류를 가리지
     * 않는다.
     */
    public static Integer resolve(PlacementType placementType, LocalDateTime startAt, LocalDateTime endAt,
                                   Integer requestedMinutes) {
        if (placementType == PlacementType.TIME_FIXED && startAt != null && endAt != null) {
            return minutesBetween(startAt, endAt);
        }
        return requestedMinutes;
    }

    /**
     * TIME_FIXED를 줄일 때의 새 종료 시각. 시작 시각은 유지한다 — 사용자가 줄이려는 것은
     * 분량이지 언제 시작할지가 아니다.
     */
    public static LocalDateTime shortenedEnd(LocalDateTime startAt, int reducedMinutes) {
        return startAt.plusMinutes(reducedMinutes);
    }
}
