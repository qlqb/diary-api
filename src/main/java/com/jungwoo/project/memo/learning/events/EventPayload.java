package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * verb별 구조화된 의미(설계 20번 §6.2). 원문 텍스트(메모·막힌 단계 글·기억 본문)를 담는 필드를 두지 않는다.
 *
 * <p>payload 안에서 다른 원천·이벤트를 가리키면 {@link #referencedSources()}·{@link #referencedEvents()}로 내놓는다 — 기록기가 전부 검증한다.
 */
public interface EventPayload {

    /** payload 안이 가리키는 원천. */
    @JsonIgnore
    default List<SourceRef> referencedSources() {
        return List.of();
    }

    /** payload 안이 가리키는 다른 이벤트 id(resolves·target·by). */
    @JsonIgnore
    default List<Long> referencedEvents() {
        return List.of();
    }

    /** RESOLVED처럼 "살아 있는 STUCK"을 가리켜야 하는 이벤트 id. */
    @JsonIgnore
    default List<Long> liveStuckRefs() {
        return List.of();
    }
}
