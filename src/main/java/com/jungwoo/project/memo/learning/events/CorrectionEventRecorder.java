package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventSourceResolver.Mode;
import com.jungwoo.project.memo.learning.events.EventSourceResolver.Resolved;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.ObjectKind;
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
 * 사용자가 적용한 정정(실제 수업 CLASS·범위 제외) → 학습 이벤트(계획 §3, §0).
 *
 * <ul>
 *   <li>범위 제외(살아 있음) → 원천마다 SCOPE_EXCLUDED{examKey}. 풀면 출력 0 판.</li>
 *   <li>실제 수업 CLASS는 토픽별 행이고 수업 날짜가 없다 — 회차 집합으로 합치지 않고 항상 과목 단위 진술
 *       STATED{claim COVERED_IN_WEEK, week, classSeq, sources}. 회차 이벤트는 수업 확인만 쓴다. 번호를 다시 매기면 바뀐 행들이 새 판.</li>
 * </ul>
 * 호출자는 과목 행을 잠근 뒤 부른다.
 */
@Component
@RequiredArgsConstructor
public class CorrectionEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final EventSourceResolver resolver;
    private final EventSync sync;
    private final EventCutover cutover;

    @Transactional(propagation = Propagation.MANDATORY)
    public void syncCourse(long userId, Long courseId) {
        if (courseId == null || eventMapper.countOwnedCourse(userId, courseId) != 1) {
            return;
        }
        EventSync.Snapshot exclusions = sync.snapshot(userId, courseId, OriginKind.SCOPE_EXCLUSION);
        for (EventSourceMapper.ExclusionRow row : sourceMapper.findExclusions(userId, courseId)) {
            List<EventDraft> drafts = new ArrayList<>();
            if ("ACTIVE".equals(row.status())) {
                for (Resolved r : sourcesOf(userId, courseId, row.topicId(), modeOf(row.createdAt()))) {
                    drafts.add(new EventDraft(Actor.ME, Verb.SCOPE_EXCLUDED, r.ref(), row.topicId(),
                            new Payloads.ScopeExcluded(row.label() == null ? "" : row.label(), false),
                            r.evidence() == null ? Evidence.INPUT : r.evidence(), r.confidence(), null, null, null));
                }
            }
            sync.apply(new EventOrigin(userId, courseId, OriginKind.SCOPE_EXCLUSION, row.exclusionId()), drafts,
                    exclusions);
        }

        EventSync.Snapshot classes = sync.snapshot(userId, courseId, OriginKind.CLASS_PROGRESS);
        for (EventSourceMapper.ClassRow row : sourceMapper.findClassRows(userId, courseId)) {
            Mode mode = modeOf(row.createdAt());
            List<SourceRef> refs = sourcesOf(userId, courseId, row.topicId(), mode).stream()
                    .map(Resolved::ref).filter(r -> r.kind() != ObjectKind.COURSE).toList();
            List<EventDraft> drafts = mode == Mode.HISTORICAL && refs.isEmpty() ? List.of()
                    : List.of(new EventDraft(Actor.ME, Verb.STATED, SourceRef.course(), row.topicId(),
                            new Payloads.Stated("COVERED_IN_WEEK", row.weekNo(), row.classSeq(), refs), Evidence.INPUT,
                            null, null, null, null));
            sync.apply(new EventOrigin(userId, courseId, OriginKind.CLASS_PROGRESS, row.progressId()), drafts, classes);
        }
    }

    /** 전환 전에 만든 정정은 백필 규칙(직접 가리킨 원천만 — 없으면 만들지 않는다, 계획 §11.1). */
    private Mode modeOf(java.time.LocalDateTime createdAt) {
        return cutover.historical(createdAt) ? Mode.HISTORICAL : Mode.LIVE;
    }

    private List<Resolved> sourcesOf(long userId, long courseId, Long topicId, Mode mode) {
        List<Resolved> sources = topicId == null ? List.of()
                : resolver.forTopic(userId, courseId, topicId, mode, Set.of());
        if (sources.isEmpty() && mode == Mode.LIVE) {
            return List.of(new Resolved(SourceRef.course(), Evidence.APPROX, EventSourceResolver.APPROX_CONFIDENCE));
        }
        return sources;
    }
}
