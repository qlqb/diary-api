package com.jungwoo.project.memo.ai.domain;

/**
 * 장기 컨텍스트에 대해 AI가 제안할 수 있는 범용 연산. "이사"/"알바 변경" 같은 생활 사건
 * 종류를 이 enum에 추가하지 않는다 — 의미 판단은 AI의 몫이고, 서버는 이 다섯 개 연산만 안다.
 */
public enum ContextChangeOperation {
    /** 새로운 장기 사실 추가. targetContextId 없음. */
    ADD,

    /** 기존 Context를 새 사실로 대체. 기존 행은 삭제하지 않고 SUPERSEDED로 보존한다. */
    SUPERSEDE,

    /** 기존 정보가 지금도 맞는지 의심되는 상태로 표시. 삭제하지 않는다. */
    MARK_STALE,

    /** 더 이상 상담/계획에 쓰지 않음으로 표시. 이력은 보존한다. */
    ARCHIVE,

    /** STALE 상태였던 Context를 사용자가 다시 유효하다고 확인. */
    CONFIRM
}
