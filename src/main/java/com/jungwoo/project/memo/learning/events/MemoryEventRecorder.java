package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventSourceMapper.ContextRow;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 학습 기억(user_contexts, 18번)·자기 점검 → 학습 이벤트(계획 §3, §3.2, §11.2).
 *
 * <p>기억 행의 이벤트는 <b>그 행의 지금 상태만으로</b> 정한다. 그래서 자동 저장·확인·다시 말함·고치기·철회·사진 단원 정정·옛 후보 적용이
 * 어느 경로로 바꾸든, 끝에 {@link #syncCourse}로 과목의 기억을 다시 맞추면 된다(바뀐 것만 새 판이 된다).
 * <ul>
 *   <li>살아 있는 행(ACTIVE·STALE): 진도 → STATED(PROGRESS), 시험 범위 → SCOPE_ANNOUNCED(AMBIGUOUS), 막힘 → STUCK, 점검 → SELF_ASSESSED.</li>
 *   <li>해결 때문에 대체된 막힘(해결 연결이 있음)은 STUCK을 유지한다. 그 밖에 대체·철회·보관된 행은 출력 0 판 — 최신 값을 철회해도 옛 값이
 *       되살아나지 않는다.</li>
 *   <li>해결은 기억 행이 아니라 막힘마다의 RESOLUTION origin: 해결 기억이 살아 있고 종류가 해결이면, 막힘의 살아 있는 STUCK마다 RESOLVED.</li>
 *   <li>전환 전에 만든 행은 백필 규칙(직접 가리킨 원천만).</li>
 * </ul>
 * 호출자는 그 과목 행을 잠근 뒤 부른다(기억 쓰기의 기존 잠금) — 과목 하나의 동기화는 한 번에 하나다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final EventSourceResolver resolver;
    private final EventSync sync;
    private final EventCutover cutover;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreLinksOf(long userId, long courseId) {
        restoreLinks(userId, courseId);
    }

    /** 막힘 → 그 막힘을 닫은 해결 기억(같은 트랜잭션). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkResolution(long userId, long difficultyId, long resolverId) {
        sourceMapper.upsertResolutionLink(userId, difficultyId, resolverId);
    }

    /**
     * 해결 기억을 고쳐 새 행이 생겼다. 연결을 새 행으로 옮긴다 — 새 행이 해결이 아니면(종류를 바꿈) 해결 이벤트는 내려가지만, 막힘은
     * 해결 연결이 남아 있어 STUCK을 잃지 않는다(막힘이 다시 열린 것으로 보인다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolverReplaced(long userId, long oldResolverId, Long newResolverId) {
        if (newResolverId != null) {
            sourceMapper.moveResolutionLinks(userId, oldResolverId, newResolverId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void syncCourse(long userId, Long courseId) {
        if (courseId == null || eventMapper.countOwnedCourse(userId, courseId) != 1) {
            return;
        }
        restoreLinks(userId, courseId);
        List<ContextRow> rows = sourceMapper.findEventContexts(userId, courseId);
        Map<Long, Long> resolverOf = new java.util.TreeMap<>();
        sourceMapper.findResolutionLinks(userId, courseId).forEach(l -> resolverOf.put(l.difficultyId(), l.resolverId()));

        EventSync.Snapshot contexts = sync.snapshot(userId, courseId, OriginKind.USER_CONTEXT);
        Map<Long, ContextRow> byId = new HashMap<>();
        for (ContextRow row : rows) {
            byId.put(row.contextId(), row);
            List<EventDraft> drafts = outputsOf(userId, courseId, row, resolverOf.containsKey(row.contextId()),
                    contexts.of(row.contextId()));
            sync.apply(new EventOrigin(userId, courseId, OriginKind.USER_CONTEXT, row.contextId()), drafts, contexts);
        }

        // 막힘의 STUCK을 다 쓴 뒤에 해결을 맞춘다(해결은 살아 있는 STUCK을 가리킨다). 연결이 사라진 해결 origin도 내린다.
        EventSync.Snapshot resolutions = sync.snapshot(userId, courseId, OriginKind.RESOLUTION);
        java.util.TreeSet<Long> difficulties = new java.util.TreeSet<>(resolverOf.keySet());
        difficulties.addAll(resolutions.written());
        for (Long difficultyId : difficulties) {
            ContextRow difficulty = byId.get(difficultyId);
            Long resolverId = resolverOf.get(difficultyId);
            List<EventDraft> drafts = difficulty == null || resolverId == null ? List.of()
                    : resolvedOf(userId, difficulty, byId.get(resolverId));
            sync.apply(new EventOrigin(userId, courseId, OriginKind.RESOLUTION, difficultyId), drafts, resolutions);
        }
    }

    /**
     * 해결 연결이 없던 때(이 기능 전)의 해결 기억도 연결한다: 막힘을 가리키는 해결 기억에서 출발해, 사용자가 고쳐 대체한 행을 따라가 지금 행에
     * 잇는다. 이미 있는 연결은 그대로 둔다. 동기화마다 돌아서, 실시간 수정과 백필 중 무엇이 먼저든 결과가 같다.
     */
    void restoreLinks(long userId, long courseId) {
        for (EventSourceMapper.ResolutionLink seed : sourceMapper.findResolverSeeds(userId, courseId)) {
            long resolver = seed.resolverId();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            boolean cycle = false;
            while (true) {
                if (!seen.add(resolver)) {
                    cycle = true;
                    break;
                }
                Long next = sourceMapper.findEditSuccessor(userId, resolver);
                if (next == null) {
                    break;
                }
                resolver = next;
            }
            if (cycle) {
                // 사슬 중간 행을 잇면 INSERT IGNORE 때문에 영영 고쳐지지 않는다 — 잇지 않고 남긴다
                log.warn("해결 연결 복원: 수정 사슬이 순환해 건너뜀(difficultyId={})", seed.difficultyId());
                continue;
            }
            sourceMapper.insertResolutionLinkIgnore(userId, seed.difficultyId(), resolver);
        }
    }

    List<EventDraft> outputsOf(long userId, long courseId, ContextRow row, boolean resolvedDifficulty,
                               List<LearningEvent> now) {
        boolean alive = "ACTIVE".equals(row.status()) || "STALE".equals(row.status());
        boolean difficulty = "DIFFICULTY".equals(row.factKind());
        if (!alive && !(difficulty && "SUPERSEDED".equals(row.status()) && resolvedDifficulty)) {
            return List.of();
        }
        boolean selfCheck = "SELF_CHECK".equals(row.sourceType());
        String kind = selfCheck ? "SELF_CHECK" : row.factKind();
        if (kind == null || "RESOLVED".equals(kind)) {
            return List.of();
        }
        Mode mode = cutover.historical(row.createdAt()) ? Mode.HISTORICAL : Mode.LIVE;
        // 기존 참조: 지금 출력의 대상과 payload 안의 원천(시험 범위의 anchor 등) 모두
        Set<String> known = new LinkedHashSet<>();
        for (EventOutputs.Output o : EventOutputs.fromRows(now, objectMapper)) {
            known.add(o.object().identity());
            o.payloadSources().forEach(s -> known.add(s.identity()));
        }
        List<Resolved> sources = resolver.forMemory(userId, courseId, row.sectionId(), row.topicPhotoId(), row.topicId(),
                mode, known);
        Evidence said = selfCheck ? Evidence.STATED : evidenceOf(row.evidenceType());
        LocalDateTime claimAt = row.saidAt() != null ? row.saidAt() : row.confirmedAt() != null ? row.confirmedAt()
                : row.createdAt();
        Long seq = row.sourceMessageId();

        List<EventDraft> out = new ArrayList<>();
        switch (kind) {
            case "EXAM_SCOPE" -> {
                // 전환 전 기억이고 직접 원천을 못 찾았으면 만들지 않는다(백필과 같은 결과, 계획 §11.1)
                if (!(mode == Mode.HISTORICAL && sources.isEmpty())) {
                    out.add(new EventDraft(Actor.CLASS, Verb.SCOPE_ANNOUNCED, SourceRef.course(), row.topicId(),
                            new Payloads.ScopeAnnounced(row.factLabel(), "UNKNOWN",
                                    sources.stream().map(Resolved::ref).toList(), "AMBIGUOUS"),
                            said, null, claimAt, seq, null));
                }
            }
            case "PROGRESS" -> {
                if (sources.isEmpty()) {
                    if (mode == Mode.LIVE) {
                        out.add(draft(Verb.STATED, SourceRef.course(), row, new Payloads.Stated("PROGRESS", null, null, null),
                                said, null, claimAt, seq));
                    }
                } else {
                    for (Resolved r : sources) {
                        out.add(draft(Verb.STATED, r.ref(), row, new Payloads.Stated("PROGRESS", null, null, null),
                                r.approximate() ? Evidence.APPROX : said, r.confidence(), claimAt, seq));
                    }
                }
            }
            case "DIFFICULTY", "SELF_CHECK" -> {
                Verb verb = difficulty ? Verb.STUCK : Verb.SELF_ASSESSED;
                EventPayload payload = difficulty ? new Payloads.Stuck()
                        : new Payloads.SelfAssessed(row.selfLevel(), "SELF_CHECK");
                if (sources.isEmpty() && mode == Mode.LIVE) {
                    sources = List.of(new Resolved(SourceRef.course(), Evidence.APPROX, EventSourceResolver.APPROX_CONFIDENCE));
                }
                for (Resolved r : sources) {
                    out.add(draft(verb, r.ref(), row, payload, r.approximate() ? Evidence.APPROX : said, r.confidence(),
                            claimAt, seq));
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** 막힘의 살아 있는 STUCK마다 RESOLVED(해결 기억이 살아 있고 종류가 해결일 때만). */
    private List<EventDraft> resolvedOf(long userId, ContextRow difficulty, ContextRow resolverRow) {
        if (resolverRow == null || !"RESOLVED".equals(resolverRow.factKind())
                || !("ACTIVE".equals(resolverRow.status()) || "STALE".equals(resolverRow.status()))) {
            return List.of();
        }
        List<EventDraft> out = new ArrayList<>();
        Evidence said = evidenceOf(resolverRow.evidenceType());
        LocalDateTime claimAt = resolverRow.saidAt() != null ? resolverRow.saidAt() : resolverRow.createdAt();
        for (LearningEvent stuck : sourceMapper.findCurrentOutputs(userId, OriginKind.USER_CONTEXT.name(),
                difficulty.contextId())) {
            if (!Verb.STUCK.name().equals(stuck.getVerb())) {
                continue;
            }
            SourceRef ref = new SourceRef(ObjectKind.valueOf(stuck.getObjectKind()), stuck.getObjectRef(),
                    stuck.getObjectFrom(), stuck.getObjectTo());
            out.add(new EventDraft(Actor.ME, Verb.RESOLVED, ref, stuck.getTopicId(),
                    new Payloads.Resolved(stuck.getEventId(), "GUIDED".equals(resolverRow.helpLevel())), said, null,
                    claimAt, resolverRow.sourceMessageId(), null));
        }
        return out;
    }

    private static EventDraft draft(Verb verb, SourceRef ref, ContextRow row, EventPayload payload, Evidence evidence,
                                    java.math.BigDecimal confidence, LocalDateTime claimAt, Long seq) {
        return new EventDraft(Actor.ME, verb, ref, row.topicId(), payload, evidence, confidence, claimAt, seq, null);
    }

    static Evidence evidenceOf(String evidenceType) {
        return "STATED".equals(evidenceType) || "SELF_REPORT".equals(evidenceType) ? Evidence.STATED : Evidence.INFERRED;
    }

}
