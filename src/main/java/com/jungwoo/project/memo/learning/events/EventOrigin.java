package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;

import java.util.Objects;

/**
 * 이벤트를 만든 원본 하나. 사용자·과목은 서버가 원본 행에서 정한 값이다.
 */
public record EventOrigin(long userId, long courseId, OriginKind kind, long id) {

    public EventOrigin {
        Objects.requireNonNull(kind, "kind");
    }
}
