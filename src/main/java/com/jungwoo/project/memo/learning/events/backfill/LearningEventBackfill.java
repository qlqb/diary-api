package com.jungwoo.project.memo.learning.events.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.learning.events.CorrectionEventRecorder;
import com.jungwoo.project.memo.learning.events.EventCutover;
import com.jungwoo.project.memo.learning.events.EventOrigin;
import com.jungwoo.project.memo.learning.events.EventSourceMapper;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.ExecutionEventRecorder;
import com.jungwoo.project.memo.learning.events.LearningEventMapper;
import com.jungwoo.project.memo.learning.events.LearningEventWriter;
import com.jungwoo.project.memo.learning.events.MaterialEventRecorder;
import com.jungwoo.project.memo.learning.events.MemoryEventRecorder;
import com.jungwoo.project.memo.learning.events.TopicEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 과거 기록을 학습 이벤트로 한 번 옮긴다(계획 §6·§11.1·§11.2·§11.7).
 *
 * <ul>
 *   <li>대상은 전환(cutover) 전에 만든 원본이고, 전환 뒤 10분이 지나야 시작한다(그 전 트랜잭션이 끝났다는 여유). 운영에서는 사용자가 새
 *       서버로 다시 띄운 것을 확인한 뒤 설정을 켠다.</li>
 *   <li>규칙은 실시간 경로와 같다 — 전환 전 원본은 직접 가리킨 원천만(근사뿐이면 만들지 않고 "미대응"으로 센다). 실시간이 먼저 쓴
 *       origin은 건너뛴다. 기억·정정은 과목 단위로 지금 상태에 맞춘다(같으면 판을 올리지 않는다).</li>
 *   <li>보고: processed·written·skipped·unmapped(원천을 찾지 못함). 기억·정정은 과목 단위라 written·unchanged는 과목 수,
 *       unmapped는 전환 전에 만든 살아 있는 원본 행 중 판이 0으로 남은 행 수다.</li>
 *   <li>한 건(또는 과목 하나) = 한 트랜잭션. 진행 위치는 같은 트랜잭션에서 올리고, 내가 lease를 쥐고 있을 때만 올린다 — lease를 잃으면
 *       그 건을 롤백하고 멈춘다(다른 서버가 이어서 한다).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningEventBackfill implements ApplicationRunner {

    static final int BATCH = 200;
    static final int LEASE_MINUTES = 5;
    static final int SETTLE_MINUTES = 10;

    public enum Source { EXECUTION_RECORD, USER_CONTEXT, CORRECTION, TOPIC, MATERIAL_LINK }

    private final BackfillMapper mapper;
    private final EventCutover cutover;
    private final TransactionTemplate tx;
    private final CourseMapper courseMapper;
    private final ExecutionRecordMapper recordMapper;
    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final LearningEventWriter writer;
    private final ExecutionEventRecorder executionEvents;
    private final MemoryEventRecorder memoryEvents;
    private final CorrectionEventRecorder correctionEvents;
    private final TopicEventRecorder topicEvents;
    private final MaterialEventRecorder materialEvents;
    private final ObjectMapper objectMapper;

    @Value("${learning.events.backfill.enabled:false}")
    private boolean enabled;

    /** 한 건 = 한 트랜잭션, READ_COMMITTED — 잠금을 기다린 뒤 최신 커밋을 본다(동기화 계약, EventSync). */
    private TransactionTemplate committedReads() {
        TransactionTemplate t = new TransactionTemplate(tx.getTransactionManager());
        t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                runOnce(UUID.randomUUID().toString(), LocalDateTime.now(), null);
            } catch (Exception e) {
                log.warn("학습 이벤트 백필 실패: {}", e.getClass().getSimpleName(), e);
            }
        }, "learning-event-backfill");
        t.setDaemon(true);
        t.start();
    }

    /**
     * @param onlyUser 테스트용 — 한 사용자만(null = 전부). 진행 위치는 원본 종류마다 하나라 운영에서는 null로만 돌린다
     * @return 원본 종류별 보고(끝내지 못한 종류는 빠진다)
     */
    public Map<Source, Map<String, Long>> runOnce(String owner, LocalDateTime now, Long onlyUser) {
        LocalDateTime before = cutover.at();
        if (now.isBefore(before.plusMinutes(SETTLE_MINUTES))) {
            log.info("학습 이벤트 백필: 전환 뒤 {}분이 지나지 않아 다음에", SETTLE_MINUTES);
            return Map.of();
        }
        Map<Source, Map<String, Long>> out = new LinkedHashMap<>();
        for (Source source : Source.values()) {
            Map<String, Long> report = runSource(source, owner, before, onlyUser);
            if (report != null) {
                out.put(source, report);
            }
        }
        return out;
    }

    /** 이번 실행의 전환 시각(과목 단위 종류의 미대응 집계에 쓴다). */
    private LocalDateTime cutoverAt;

    private Map<String, Long> runSource(Source source, String owner, LocalDateTime before, Long onlyUser) {
        this.cutoverAt = before;
        mapper.ensureRow(source.name());
        if (mapper.acquire(source.name(), owner, LEASE_MINUTES) != 1) {
            return null; // 끝났거나 다른 서버가 돌리는 중
        }
        BackfillMapper.Progress progress = mapper.find(source.name());
        Map<String, Long> report = readReport(progress.report());
        long last = progress.lastId() == null ? 0 : progress.lastId();
        while (true) {
            List<BackfillMapper.Unit> units = next(source, last, before, onlyUser);
            if (units.isEmpty()) {
                if (mapper.finish(source.name(), owner, json(report)) != 1) {
                    log.warn("학습 이벤트 백필 {}: lease를 잃어 끝을 기록하지 못함", source);
                    return null;
                }
                log.info("학습 이벤트 백필 {} 끝: {}", source, report);
                return report;
            }
            for (BackfillMapper.Unit unit : units) {
                Map<String, Long> start = report;
                Map<String, Long> after = committedReads().execute(s -> {
                    Map<String, Long> delta = new LinkedHashMap<>();
                    process(source, unit, delta);
                    Map<String, Long> merged = merge(start, delta);
                    if (mapper.advance(source.name(), owner, unit.id(), json(merged), LEASE_MINUTES) != 1) {
                        s.setRollbackOnly(); // lease를 잃었다 — 이 건은 버리고 멈춘다(다른 서버가 이어서 한다)
                        return null;
                    }
                    return merged;
                });
                if (after == null) {
                    log.warn("학습 이벤트 백필 {}: lease를 잃어 멈춤(lastId={})", source, last);
                    return null;
                }
                report = after;
                last = unit.id();
            }
        }
    }

    private List<BackfillMapper.Unit> next(Source source, long after, LocalDateTime before, Long onlyUser) {
        return switch (source) {
            case EXECUTION_RECORD -> mapper.nextRecords(after, before, BATCH, onlyUser);
            case USER_CONTEXT -> mapper.nextContextCourses(after, before, BATCH, onlyUser);
            case CORRECTION -> mapper.nextCorrectionCourses(after, before, BATCH, onlyUser);
            case TOPIC -> mapper.nextTopics(after, before, BATCH, onlyUser);
            case MATERIAL_LINK -> mapper.nextLinks(after, before, BATCH, onlyUser);
        };
    }

    /** 한 단위 처리(트랜잭션 안). delta에 processed·written·skipped·unmapped를 센다. */
    private void process(Source source, BackfillMapper.Unit unit, Map<String, Long> delta) {
        long userId = unit.userId();
        add(delta, "processed");
        switch (source) {
            case EXECUTION_RECORD -> {
                if (recordMapper.lockByIdAndUserId(unit.id(), userId) == null) {
                    return;
                }
                EventSourceMapper.ExecutionFacts f = sourceMapper.findExecutionFacts(userId, unit.id());
                if (f == null || f.courseId() == null || eventMapper.countOwnedCourse(userId, f.courseId()) != 1) {
                    add(delta, "skipped");
                    return;
                }
                once(new EventOrigin(userId, f.courseId(), OriginKind.EXECUTION_RECORD, unit.id()), delta,
                        () -> executionEvents.record(userId, unit.id()));
            }
            case USER_CONTEXT -> {
                if (courseMapper.findByIdAndUserIdForUpdate(unit.id(), userId) == null) {
                    return;
                }
                List<String> kinds = List.of(OriginKind.USER_CONTEXT.name(), OriginKind.RESOLUTION.name());
                long before = mapper.sumRevisions(userId, unit.id(), kinds);
                memoryEvents.syncCourse(userId, unit.id()); // 해결 연결 복원 포함
                add(delta, mapper.sumRevisions(userId, unit.id(), kinds) > before ? "written" : "unchanged");
                addAll(delta, "unmapped", mapper.countUnwrittenContexts(userId, unit.id(), this.cutoverAt));
            }
            case CORRECTION -> {
                if (courseMapper.findByIdAndUserIdForUpdate(unit.id(), userId) == null) {
                    return;
                }
                List<String> kinds = List.of(OriginKind.SCOPE_EXCLUSION.name(), OriginKind.CLASS_PROGRESS.name());
                long before = mapper.sumRevisions(userId, unit.id(), kinds);
                correctionEvents.syncCourse(userId, unit.id());
                add(delta, mapper.sumRevisions(userId, unit.id(), kinds) > before ? "written" : "unchanged");
                addAll(delta, "unmapped", mapper.countUnwrittenCorrections(userId, unit.id(), this.cutoverAt));
            }
            case TOPIC -> {
                // 실시간과 같은 순서: 원본(토픽·진도) → 교재 별칭 → origin
                if (mapper.lockTopic(userId, unit.id()) == null) {
                    return;
                }
                mapper.lockTopicProgress(userId, unit.id());
                EventSourceMapper.TopicState t = sourceMapper.findTopicState(userId, unit.id());
                if (t == null || t.courseId() == null) {
                    return;
                }
                boolean progressDone = t.progressId() == null
                        || writer.written(new EventOrigin(userId, t.courseId(), OriginKind.TOPIC_PROGRESS, t.progressId()));
                boolean markDone = writer.written(new EventOrigin(userId, t.courseId(), OriginKind.TOPIC_MARK, unit.id()));
                if (progressDone && markDone) {
                    add(delta, "skipped");
                    return;
                }
                topicEvents.backfill(userId, unit.id(), !progressDone, !markDone);
                boolean wrote = (!progressDone && t.progressId() != null && revisionOf(userId, OriginKind.TOPIC_PROGRESS, t.progressId()) > 0)
                        || (!markDone && revisionOf(userId, OriginKind.TOPIC_MARK, unit.id()) > 0);
                add(delta, wrote ? "written" : "unmapped");
            }
            case MATERIAL_LINK -> {
                if (mapper.lockMaterialLink(userId, unit.id()) == null) {
                    return;
                }
                EventSourceMapper.MaterialLinkRow link = sourceMapper.findMaterialLink(userId, unit.id());
                if (link == null || eventMapper.countOwnedCourse(userId, link.courseId()) != 1) {
                    add(delta, "skipped");
                    return;
                }
                once(new EventOrigin(userId, link.courseId(), OriginKind.MATERIAL_LINK, unit.id()), delta,
                        () -> materialEvents.record(userId, unit.id()));
            }
        }
    }

    /** 실시간이 먼저 쓴 origin은 건너뛰고, 아니면 기록한 뒤 판이 생겼는지(원천을 찾았는지) 센다. */
    private void once(EventOrigin origin, Map<String, Long> delta, Runnable record) {
        if (writer.written(origin)) {
            add(delta, "skipped");
            return;
        }
        record.run();
        LearningEventMapper.OriginRow row = eventMapper.lockOrigin(origin.userId(), origin.kind().name(), origin.id());
        add(delta, row != null && row.currentRevision() > 0 ? "written" : "unmapped");
    }

    private int revisionOf(long userId, OriginKind kind, long id) {
        LearningEventMapper.OriginRow row = eventMapper.findOrigin(userId, kind.name(), id);
        return row == null ? 0 : row.currentRevision();
    }

    private static void add(Map<String, Long> m, String key) {
        m.merge(key, 1L, Long::sum);
    }

    private static void addAll(Map<String, Long> m, String key, long n) {
        if (n > 0) {
            m.merge(key, n, Long::sum);
        }
    }

    private static Map<String, Long> merge(Map<String, Long> base, Map<String, Long> delta) {
        Map<String, Long> out = new LinkedHashMap<>(base);
        delta.forEach((k, v) -> out.merge(k, v, Long::sum));
        return out;
    }

    private Map<String, Long> readReport(String json) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            objectMapper.readTree(json).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asLong()));
        } catch (Exception e) {
            log.warn("백필 보고를 읽지 못해 새로 센다: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    private String json(Map<String, Long> report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            return "{}";
        }
    }
}
