package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventSourceResolver.Mode;
import com.jungwoo.project.memo.learning.events.EventSourceResolver.Resolved;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.ObjectKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실행 기록(완료·일부 수행·회고 수정) → ATTEMPTED(+STUCK) (계획 §3, §3.1, §11.1).
 *
 * <ul>
 *   <li>완료는 활동 수행이지 이해 확정이 아니다 — outcome DONE_UNGRADED. 도움 수준을 답하지 않았으면 UNKNOWN(SOLO로 보지 않는다).</li>
 *   <li>막힌 단계를 적었거나 걸린 점이 개념(CONCEPT)이면 같은 원천에 STUCK.</li>
 *   <li>원천은 첫 판에서 정하고 바꾸지 않는다 — 회고 수정은 수행 정보만 다시 계산한다(그 사이 토픽 연결이 바뀌어도 공부한 대상은 그대로).</li>
 *   <li>과목이 없거나 그 사용자의 과목이 아니면 대상 밖 — 기록하지 않고 원래 동작은 그대로다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final EventSourceResolver resolver;
    private final LearningEventWriter writer;
    private final EventCutover cutover;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long userId, Long recordId) {
        if (userId == null || recordId == null) {
            return;
        }
        EventSourceMapper.ExecutionFacts f = sourceMapper.findExecutionFacts(userId, recordId);
        if (f == null || f.courseId() == null) {
            return;
        }
        if (eventMapper.countOwnedCourse(userId, f.courseId()) != 1) {
            log.warn("실행 기록 이벤트: 그 사용자의 과목이 아니라 건너뜀(recordId={})", recordId);
            return;
        }
        EventOrigin origin = new EventOrigin(userId, f.courseId(), OriginKind.EXECUTION_RECORD, recordId);

        List<Resolved> sources = fixedSources(userId, recordId);
        if (sources.isEmpty()) {
            Mode mode = cutover.historical(f.createdAt()) ? Mode.HISTORICAL : Mode.LIVE;
            sources = resolver.forExecution(userId, f.courseId(), f.executionItemId(), f.topicId(), mode, Set.of());
            if (sources.isEmpty()) {
                if (mode == Mode.HISTORICAL) {
                    log.info("실행 기록 이벤트: 전환 전 기록의 원천을 확정하지 못해 만들지 않음(recordId={})", recordId);
                    return;
                }
                sources = List.of(new Resolved(SourceRef.course(), Evidence.APPROX, EventSourceResolver.APPROX_CONFIDENCE));
            }
        }
        writer.write(origin, drafts(f, sources));
    }

    /** 이미 판이 있으면 그 판의 원천(첫 판에서 정한 것) 그대로. */
    private List<Resolved> fixedSources(long userId, long recordId) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (LearningEvent e : sourceMapper.findCurrentOutputs(userId, OriginKind.EXECUTION_RECORD.name(), recordId)) {
            if (!Verb.ATTEMPTED.name().equals(e.getVerb())) {
                continue;
            }
            SourceRef ref = new SourceRef(ObjectKind.valueOf(e.getObjectKind()), e.getObjectRef(), e.getObjectFrom(),
                    e.getObjectTo());
            Evidence ev = Evidence.valueOf(e.getEvidence());
            out.putIfAbsent(ref.identity(), new Resolved(ref, ev == Evidence.LOGGED ? null : ev, e.getConfidence()));
        }
        return new ArrayList<>(out.values());
    }

    static List<EventDraft> drafts(EventSourceMapper.ExecutionFacts f, List<Resolved> sources) {
        boolean partial = "PARTIAL".equals(f.outcome());
        boolean hasStuckStep = f.stuckStep() != null && !f.stuckStep().isBlank();
        String support = "SOLO".equals(f.supportLevel()) || "GUIDED".equals(f.supportLevel()) ? f.supportLevel() : "UNKNOWN";
        Payloads.Attempted attempted = new Payloads.Attempted(partial ? "PARTIAL" : "DONE_UNGRADED", support,
                f.blockerKind(), hasStuckStep, partial ? f.completionPercent() : null);
        boolean stuck = hasStuckStep || "CONCEPT".equals(f.blockerKind());
        java.time.LocalDateTime occurred = f.endedAt() != null ? f.endedAt() : f.recordedAt();

        List<EventDraft> out = new ArrayList<>();
        for (Resolved r : sources) {
            Evidence ev = r.evidence() == null ? Evidence.LOGGED : r.evidence();
            out.add(new EventDraft(Actor.ME, Verb.ATTEMPTED, r.ref(), f.topicId(), attempted, ev, r.confidence(), null,
                    null, occurred));
            if (stuck) {
                out.add(new EventDraft(Actor.ME, Verb.STUCK, r.ref(), f.topicId(), new Payloads.Stuck(), ev,
                        r.confidence(), null, null, occurred));
            }
        }
        return out;
    }
}
