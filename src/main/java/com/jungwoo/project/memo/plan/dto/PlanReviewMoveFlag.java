package com.jungwoo.project.memo.plan.dto;

/**
 * 주 분류와 별개로 붙는 부가 플래그. "완료했는데 다른 날 했다"가 흔하므로 둘이 동시에
 * 성립해야 한다.
 *
 * ★ UNSCHEDULED → DATE_ONLY는 SCHEDULED(배치)이지 MOVED(이동)가 아니다. 계획대로 진행된
 * 정상 경로를 "옮겼다"고 말하면 사용자가 무언가 어긋났다고 읽는다.
 */
public enum PlanReviewMoveFlag {

    /** 스냅샷에도 날짜가 있었고 지금 다른 날짜다. */
    MOVED,

    /** 스냅샷에는 날짜가 없었고 지금 생겼다. 이동이 아니라 배치다. */
    SCHEDULED,

    /** 스냅샷에는 날짜가 있었는데 지금 없다. */
    UNPLACED_AGAIN
}
