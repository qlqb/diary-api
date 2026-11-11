package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 기록할 이벤트 하나(아직 판·번호가 없음). 사용자·과목·원본은 {@link EventOrigin}이 정한다 — 요청 값으로 만들지 않는다.
 *
 * @param topicId    호환 앵커(표시·디버그용). 없으면 null
 * @param confidence 0~1(근사·추론일 때). 없으면 null
 * @param claimAt    주장 시각(발화·입력 시각) — 같은 대상의 "현재 값"을 가르는 순서
 * @param claimSeq   같은 시각 안의 순서(메시지 번호)
 * @param occurredAt 일어난 때. 모르면 null
 */
public record EventDraft(Actor actor, Verb verb, SourceRef object, Long topicId, EventPayload payload,
                         Evidence evidence, BigDecimal confidence, LocalDateTime claimAt, Long claimSeq,
                         LocalDateTime occurredAt) {

    public EventDraft {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(evidence, "evidence");
        if (confidence != null && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("confidence는 0~1");
        }
    }

    public static EventDraft of(Actor actor, Verb verb, SourceRef object, EventPayload payload, Evidence evidence) {
        return new EventDraft(actor, verb, object, null, payload, evidence, null, null, null, null);
    }

    public EventDraft withTopic(Long id) {
        return new EventDraft(actor, verb, object, id, payload, evidence, confidence, claimAt, claimSeq, occurredAt);
    }

    public EventDraft withConfidence(BigDecimal c) {
        return new EventDraft(actor, verb, object, topicId, payload, evidence, c, claimAt, claimSeq, occurredAt);
    }

    public EventDraft withClaim(LocalDateTime at, Long seq) {
        return new EventDraft(actor, verb, object, topicId, payload, evidence, confidence, at, seq, occurredAt);
    }

    public EventDraft withOccurredAt(LocalDateTime at) {
        return new EventDraft(actor, verb, object, topicId, payload, evidence, confidence, claimAt, claimSeq, at);
    }
}
