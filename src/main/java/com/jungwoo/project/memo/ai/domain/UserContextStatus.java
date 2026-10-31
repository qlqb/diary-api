package com.jungwoo.project.memo.ai.domain;

/** user_contexts.status 체크 제약과 1:1 대응한다. 물리 삭제 대신 이 상태만 바뀐다. */
public enum UserContextStatus {
    ACTIVE,
    STALE,
    SUPERSEDED,
    ARCHIVED,

    /** 사용자가 지웠다(철회). 상담·계획에 쓰지 않고, 같은 내용을 다시 저장하지도 않는다. */
    WITHDRAWN
}
