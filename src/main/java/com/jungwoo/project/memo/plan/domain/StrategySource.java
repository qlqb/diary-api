package com.jungwoo.project.memo.plan.domain;

/**
 * 이 전략이 어디서 왔는가.
 *
 * 전략의 유효기간은 그것을 만든 버전의 end_date다 — 별도 validUntil을 두지 않는다.
 * 새 계획 기간이 원본 버전의 end_date 안이면 그때 내린 판단이 아직 유효하므로 REUSED로
 * 이어받을 수 있고, 넘어가면 다시 판단해야 하므로 NEW다.
 */
public enum StrategySource {

    /** 이번에 새로 판단했다. */
    NEW,

    /** 이전 버전의 판단을 그대로 이어받았다. reusedFromVersionId가 그 출처다. */
    REUSED
}
