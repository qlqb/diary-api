package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventSourceResolver.Mode;
import com.jungwoo.project.memo.learning.events.EventSourceResolver.Resolved;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 토픽 진도·표식 → 자기 평가(계획 §3).
 *
 * <ul>
 *   <li>진도를 "익힘(LEARNED)"으로 바꾼 것만 SELF_ASSESSED(KNOW). 진행 중·시작 전으로 바꾸면 출력 0 판 — 이전 "익힘"이 내려간다.</li>
 *   <li>표식 「이미 알아요(KNOWN)」만 SELF_ASSESSED(KNOW). 「나중에(DEFER)」는 이해 평가가 아니다 — 지우기와 같이 출력 0.</li>
 *   <li>실행 완료가 진도를 자동으로 바꾸는 길(recordExecutionCompleted)은 여기로 오지 않는다(실행 기록 이벤트가 이미 있다).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TopicEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final EventSourceResolver resolver;
    private final LearningEventWriter writer;

    @Transactional(propagation = Propagation.MANDATORY)
    public void progressChanged(long userId, long topicId) {
        progress(userId, topicId, Mode.LIVE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markChanged(long userId, long topicId) {
        mark(userId, topicId, Mode.LIVE);
    }

    /** 백필(전환 전 상태): 목차 토픽만 — 근사 원천이면 만들지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void backfill(long userId, long topicId, boolean progress, boolean mark) {
        if (progress) {
            progress(userId, topicId, Mode.HISTORICAL);
        }
        if (mark) {
            mark(userId, topicId, Mode.HISTORICAL);
        }
    }

    private void progress(long userId, long topicId, Mode mode) {
        EventSourceMapper.TopicState t = sourceMapper.findTopicState(userId, topicId);
        if (t == null || t.courseId() == null || t.progressId() == null) {
            return;
        }
        EventOrigin origin = new EventOrigin(userId, t.courseId(), OriginKind.TOPIC_PROGRESS, t.progressId());
        boolean learned = "LEARNED".equals(t.progressStatus());
        writer.write(origin, learned ? knows(userId, t, "STATUS", mode) : List.of());
    }

    private void mark(long userId, long topicId, Mode mode) {
        EventSourceMapper.TopicState t = sourceMapper.findTopicState(userId, topicId);
        if (t == null || t.courseId() == null) {
            return;
        }
        EventOrigin origin = new EventOrigin(userId, t.courseId(), OriginKind.TOPIC_MARK, topicId);
        boolean known = "KNOWN".equals(t.userMark());
        writer.write(origin, known ? knows(userId, t, "MARK", mode) : List.of());
    }

    /** "이 토픽을 안다" — 목차 토픽이면 그 항목, 아니면 연결 구간(근사), 둘 다 없으면 과목 + 토픽 앵커. */
    List<EventDraft> knows(long userId, EventSourceMapper.TopicState t, String via, Mode mode) {
        List<Resolved> sources = resolver.forTopic(userId, t.courseId(), t.topicId(), mode, Set.of());
        if (sources.isEmpty()) {
            if (mode == Mode.HISTORICAL) {
                return List.of();
            }
            sources = List.of(new Resolved(SourceRef.course(), Evidence.APPROX, EventSourceResolver.APPROX_CONFIDENCE));
        }
        List<EventDraft> out = new ArrayList<>();
        for (Resolved r : sources) {
            out.add(new EventDraft(Actor.ME, Verb.SELF_ASSESSED, r.ref(), t.topicId(), new Payloads.SelfAssessed("KNOW", via),
                    r.evidence() == null ? Evidence.STATED : r.evidence(), r.confidence(), null, null, null));
        }
        return out;
    }
}
