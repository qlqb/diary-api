package com.jungwoo.project.memo.learning.domain;

/** topic_change_proposals.status. */
public enum TopicChangeProposalStatus {
    PROPOSED,
    APPLIED,
    DISMISSED,
    /** 적용 시 트리 버전이 달라 적용하지 못했다. 다시 분석해야 한다. */
    CONFLICT,
    /** 자료·연결·프로젝트가 사라져 더는 의미가 없다. */
    STALE,
    /** 분석은 끝났지만 제안할 변경이 없었다. */
    EMPTY,
    /**
     * 자료별 변경안이 프로젝트 단위 정리로 옮겨가면서 물러난 것. 지우지 않고 이력으로 남긴다 —
     * 사용자가 검토하던 내용이고, 무엇이 있었는지 볼 수 있어야 한다. 되돌리려면
     * superseded_from에 적힌 원래 상태를 쓰면 된다(2026-09-21 마이그레이션 참고).
     */
    SUPERSEDED
}
