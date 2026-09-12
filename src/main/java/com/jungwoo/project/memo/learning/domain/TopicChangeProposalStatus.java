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
    EMPTY
}
