package com.jungwoo.project.memo.learning.events.session;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.learning.events.EventDraft;
import com.jungwoo.project.memo.learning.events.EventOrigin;
import com.jungwoo.project.memo.learning.events.EventOutputs;
import com.jungwoo.project.memo.learning.events.EventSourceMapper;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.ObjectKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import com.jungwoo.project.memo.learning.events.LearningEvent;
import com.jungwoo.project.memo.learning.events.LearningEventMapper;
import com.jungwoo.project.memo.learning.events.LearningEventWriter;
import com.jungwoo.project.memo.learning.events.Payloads;
import com.jungwoo.project.memo.learning.events.SourceRef;
import com.jungwoo.project.memo.routine.RoutineExceptionMapper;
import com.jungwoo.project.memo.routine.RoutineMapper;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService.ClassSlot;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 수업 회차와 수업 확인(설계 20번 §5.1·§8, 계획 §4·§11.4·§11.8).
 *
 * <ul>
 *   <li>회차는 (루틴, 원래 날짜) 하나. 처음 필요할 때 만들고 그때의 시각을 스냅샷으로 남긴다. 시간표를 고쳐도 같은 회차다.</li>
 *   <li>수업 확인은 회차별 <b>다룬 원천 집합</b>(COVERED_IN_CLASS)이다. 누적 지점이 아니다 — 건너뛰기·재방문이 그대로 남는다.</li>
 *   <li>일정 예외(휴강·보강)는 그 회차가 있으면 CANCELLED·SESSION_MOVED로 남는다(origin = 예외 행). 날짜를 바꾸면 옛 회차의 표시는
 *       판 교체로 내려가고, 지우면 지우기 전에 출력 0 판을 쓴다.</li>
 *   <li>시간표에서 휴강으로 둔 회차는 확인 목록에 넣지 않는다(이미 안다).</li>
 * </ul>
 * 잠금: 수업 확인 = 과목 행 → 루틴 행(회차 id 순) → origin. 일정 예외 = 루틴 행 → origin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassSessionService {

    static final int MAX_ITEMS = 20;
    static final int MAX_SOURCES = 30;
    static final int MAX_DAYS = 28;
    static final int MAX_OPTIONS = 80;

    private final ClassSessionMapper sessionMapper;
    private final RoutineMapper routineMapper;
    private final RoutineReader routineReader;
    private final RoutineExceptionMapper exceptionMapper;
    private final RoutineOccurrenceService occurrenceService;
    private final CourseMapper courseMapper;
    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final LearningEventWriter writer;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemDefaultZone();

    /** 시간표와 같은 시간대로 "끝난 수업"을 본다(RoutineService와 같은 설정). */
    @org.springframework.beans.factory.annotation.Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    void setZone(String zone) {
        this.clock = Clock.system(java.time.ZoneId.of(zone));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ===== 조회 =====

    public record SectionOption(String ref, Long sectionId, Long materialId, String materialName, String title,
                                Integer pageFrom, Integer pageTo) {
    }

    /**
     * 확인할 회차 하나.
     *
     * @param revision 지금 확인의 판(0 = 아직). 확인할 때 expectedRevision으로 돌려준다
     * @param defaults 기본값(지난 확인의 다음 구간 → 그 뒤 새로 올라온 자료의 첫 구간)
     */
    public record PendingSession(Long routineId, LocalDate sourceDate, LocalDateTime startAt, LocalDateTime endAt,
                                 boolean moved, int revision, List<SectionOption> defaults) {
    }

    public record PendingView(Long courseId, boolean promptEnabled, List<PendingSession> sessions,
                              List<SectionOption> options) {
    }

    @Transactional(readOnly = true)
    public PendingView pending(Long userId, Long courseId, int days) {
        Boolean enabled = sessionMapper.findPromptEnabled(userId, courseId);
        if (enabled == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (!enabled) {
            return new PendingView(courseId, false, List.of(), List.of());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate to = now.toLocalDate();
        LocalDate from = to.minusDays(Math.max(1, Math.min(days, MAX_DAYS)) - 1L);

        List<ClassSlot> slots = occurrenceService.classSlots(userId, courseId, from, to);
        Map<String, ClassSessionMapper.SessionRow> sessions = new HashMap<>();
        java.util.Set<Long> routineIds = new java.util.HashSet<>();
        slots.forEach(sl -> routineIds.add(sl.routineId()));
        if (!routineIds.isEmpty()) {
            // 과목과 무관하게 — 루틴의 과목을 바꿨으면 옛 회차는 옛 과목의 것이다
            sessionMapper.findByRoutines(userId, routineIds, from, to)
                    .forEach(r -> sessions.put(key(r.routineId(), r.sourceDate()), r));
        }
        // 지난 확인을 찾으려고 창보다 넓게(기본값의 "지난 회차"는 창 밖일 수 있다)
        List<ClassSessionMapper.SessionRow> history = sessionMapper.findByCourse(userId, courseId, from.minusDays(120), to);
        Map<Long, Integer> revisions = new HashMap<>();
        sourceMapper.findOriginRevisions(userId, courseId, OriginKind.CLASS_INPUT.name())
                .forEach(r -> revisions.put(r.originId(), r.currentRevision()));
        Map<Long, List<LearningEvent>> confirmed = new HashMap<>();
        for (LearningEvent e : sourceMapper.findCurrentOutputsOfCourse(userId, courseId, OriginKind.CLASS_INPUT.name())) {
            confirmed.computeIfAbsent(e.getOriginId(), k -> new ArrayList<>()).add(e);
        }

        List<SectionOption> options = orderedSections(userId, courseId);
        List<PendingSession> out = new ArrayList<>();
        for (ClassSlot slot : slots) {
            if (slot.cancelled() || slot.endAt().isAfter(now)) {
                continue;
            }
            ClassSessionMapper.SessionRow session = sessions.get(key(slot.routineId(), slot.sourceDate()));
            if (session != null && !Objects.equals(session.courseId(), courseId)) {
                continue; // 다른 과목으로 이미 기록된 회차 — 여기서는 확인할 수 없다
            }
            int revision = session == null ? 0 : revisions.getOrDefault(session.sessionId(), 0);
            if (session != null && confirmed.containsKey(session.sessionId())) {
                continue; // 이미 확인함(다룸·휴강·결석·자료 미정)
            }
            out.add(new PendingSession(slot.routineId(), slot.sourceDate(), slot.startAt(), slot.endAt(), slot.moved(),
                    revision, defaults(slot, history, confirmed, options)));
        }
        return new PendingView(courseId, true, out, options.size() > MAX_OPTIONS ? options.subList(0, MAX_OPTIONS) : options);
    }

    /** 지난 확인 회차의 마지막 원천 다음 구간, 그 뒤 새로 연결된 수업자료의 첫 구간. */
    private List<SectionOption> defaults(ClassSlot slot, List<ClassSessionMapper.SessionRow> history,
                                         Map<Long, List<LearningEvent>> confirmed, List<SectionOption> options) {
        ClassSessionMapper.SessionRow last = history.stream()
                .filter(s -> s.sourceDate().isBefore(slot.sourceDate()) && confirmed.containsKey(s.sessionId()))
                .max(Comparator.comparing(ClassSessionMapper.SessionRow::sourceDate)
                        .thenComparing(ClassSessionMapper.SessionRow::startAt))
                .orElse(null);
        List<SectionOption> out = new ArrayList<>();
        if (last != null) {
            int lastIndex = -1;
            for (LearningEvent e : confirmed.get(last.sessionId())) {
                for (String ref : sectionRefsIn(e.getPayload())) {
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i).ref().equals(ref)) {
                            lastIndex = Math.max(lastIndex, i);
                        }
                    }
                }
            }
            if (lastIndex >= 0 && lastIndex + 1 < options.size()) {
                out.add(options.get(lastIndex + 1));
            }
        }
        return out;
    }

    private List<String> sectionRefsIn(String payload) {
        List<String> refs = new ArrayList<>();
        try {
            objectMapper.readTree(payload).path("sources").forEach(n -> {
                if ("SECTION".equals(n.path("kind").asText())) {
                    refs.add(n.path("ref").asText());
                }
            });
        } catch (Exception e) {
            log.warn("수업 확인 payload를 읽지 못했다: {}", e.getClass().getSimpleName());
        }
        return refs;
    }

    /** 수업 순서: 사용자가 확인한 주차 → 파일명 → 연결 시각, 자료 안은 구간 순서. */
    List<SectionOption> orderedSections(Long userId, Long courseId) {
        List<ClassSessionMapper.SectionOptionRow> rows = sessionMapper.findCourseSections(userId, courseId);
        Map<Long, ClassSessionMapper.SectionOptionRow> firstOfMaterial = new LinkedHashMap<>();
        rows.forEach(r -> firstOfMaterial.putIfAbsent(r.materialId(), r));
        List<ClassSessionMapper.SectionOptionRow> materials = new ArrayList<>(firstOfMaterial.values());
        materials.sort(Comparator
                .comparing((ClassSessionMapper.SectionOptionRow r) -> r.minWeek() == null ? Integer.MAX_VALUE : r.minWeek())
                .thenComparing(r -> r.materialName() == null ? "" : r.materialName(), ClassSessionService::natural)
                .thenComparing(r -> r.linkedAt() == null ? LocalDateTime.MIN : r.linkedAt())
                .thenComparing(ClassSessionMapper.SectionOptionRow::materialId));
        List<SectionOption> out = new ArrayList<>();
        for (ClassSessionMapper.SectionOptionRow m : materials) {
            for (ClassSessionMapper.SectionOptionRow r : rows) {
                if (r.materialId().equals(m.materialId())) {
                    out.add(new SectionOption(SourceRef.section(r.sectionId()).ref(), r.sectionId(), r.materialId(),
                            r.materialName(), r.title(), r.pageFrom(), r.pageTo()));
                }
            }
        }
        return out;
    }

    /** 파일명 안의 숫자를 수로 비교(ch2 < ch10). */
    static int natural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                int c = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (c != 0) {
                    return c;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    // ===== 확인 =====

    public enum Action { COVERED, UNKNOWN_CONTENT, CANCELLED, ABSENT }

    public record SourceInput(String kind, String ref, Integer from, Integer to) {
    }

    public record ConfirmItem(Long routineId, LocalDate sourceDate, Integer expectedRevision, Action action,
                              List<SourceInput> sources) {
    }

    public record Confirmed(Long routineId, LocalDate sourceDate, Long sessionId, int revision) {
    }

    /**
     * 수업 확인(수업 후 입력·주간 확인 공용). 요청 전체가 한 트랜잭션이다 — 하나라도 충돌하면 아무것도 저장하지 않는다.
     * 같은 내용을 다시 보내면 성공(재전송), 다른 기기에서 먼저 바꿨으면(판이 다르고 내용도 다름) 409.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Confirmed> confirm(Long userId, Long courseId, List<ConfirmItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS || items.stream().anyMatch(ClassSessionService::malformed)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (courseMapper.findByIdAndUserIdForUpdate(courseId, userId) == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        // 고를 수 있는 원천: 이 과목에 연결된 수업자료(구간·자료 전체). 그 밖은 기록기 검증까지 가지 않고 400.
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (SectionOption o : orderedSections(userId, courseId)) {
            allowed.add(o.ref());
        }
        for (Long materialId : sessionMapper.findClassMaterialIds(userId, courseId)) {
            allowed.add(SourceRef.material(materialId).ref());
        }
        List<ConfirmItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(ConfirmItem::routineId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ConfirmItem::sourceDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Confirmed> out = new ArrayList<>();
        for (ConfirmItem item : sorted) {
            ClassSessionMapper.SessionRow session = ensureLocked(userId, item.routineId(), item.sourceDate());
            if (!Objects.equals(session.courseId(), courseId)) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
            EventOrigin origin = new EventOrigin(userId, courseId, OriginKind.CLASS_INPUT, session.sessionId());
            List<EventDraft> drafts = drafts(session, item);
            for (EventDraft d : drafts) {
                if (d.payload().referencedSources().stream().anyMatch(s -> !allowed.contains(s.ref()))) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            int current = revisionOf(userId, session.sessionId());
            // 같은 확인을 다시 보냈으면(확인 시각만 다름) 그대로 둔다 — 재전송은 성공이다
            boolean same = current > 0 && sameIgnoringClaim(EventOutputs.normalize(drafts, objectMapper),
                    EventOutputs.fromRows(currentOutputs(userId, session.sessionId()), objectMapper));
            if (!same) {
                int expected = item.expectedRevision() == null ? 0 : item.expectedRevision();
                if (expected != current) {
                    throw new ConflictException(ErrorCode.VERSION_CONFLICT);
                }
                writer.write(origin, drafts);
            }
            out.add(new Confirmed(item.routineId(), item.sourceDate(), session.sessionId(),
                    revisionOf(userId, session.sessionId())));
        }
        return out;
    }

    /** 요청 모양 검사(기록기 검증까지 가지 않고 400). */
    private static boolean malformed(ConfirmItem item) {
        if (item == null || item.routineId() == null || item.sourceDate() == null || item.action() == null
                || (item.expectedRevision() != null && item.expectedRevision() < 0)) {
            return true;
        }
        List<SourceInput> sources = item.sources() == null ? List.of() : item.sources();
        if (sources.size() > MAX_SOURCES || (item.action() != Action.COVERED && !sources.isEmpty())) {
            return true;
        }
        for (SourceInput in : sources) {
            if (in == null || in.kind() == null || in.ref() == null || in.from() != null || in.to() != null) {
                return true;
            }
            boolean shaped = ("SECTION".equals(in.kind()) && in.ref().matches("s:[1-9][0-9]{0,18}"))
                    || ("MATERIAL".equals(in.kind()) && in.ref().matches("m:[1-9][0-9]{0,18}"));
            if (!shaped) {
                return true;
            }
        }
        return false;
    }

    /** 확인 시각을 빼고 같은 뜻인가. */
    private static boolean sameIgnoringClaim(List<EventOutputs.Output> a, List<EventOutputs.Output> b) {
        return EventOutputs.sameOutputs(withoutClaim(a), withoutClaim(b));
    }

    private static List<EventOutputs.Output> withoutClaim(List<EventOutputs.Output> outs) {
        return outs.stream().map(o -> new EventOutputs.Output(o.actor(), o.verb(), o.objectKind(), o.objectRef(), o.from(),
                o.to(), o.topicId(), o.payloadJson(), o.evidence(), o.confidence(), null, null, o.occurredAt(),
                o.payloadSources(), o.eventRefs(), o.liveStuckRefs())).toList();
    }

    private List<EventDraft> drafts(ClassSessionMapper.SessionRow session, ConfirmItem item) {
        SourceRef target = SourceRef.session(session.sessionId());
        LocalDateTime now = LocalDateTime.now(clock);
        return switch (item.action()) {
            case COVERED -> {
                List<SourceRef> sources = sourcesOf(item.sources());
                if (sources.isEmpty()) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                yield List.of(new EventDraft(Actor.CLASS, Verb.COVERED_IN_CLASS, target, null,
                        new Payloads.Covered(sources, null), Evidence.INPUT, null, now, null, session.startAt()));
            }
            case UNKNOWN_CONTENT -> List.of(new EventDraft(Actor.CLASS, Verb.COVERED_IN_CLASS, target, null,
                    new Payloads.Covered(List.of(), true), Evidence.INPUT, null, now, null, session.startAt()));
            case CANCELLED -> List.of(new EventDraft(Actor.CLASS, Verb.CANCELLED, target, null, new Payloads.Empty(),
                    Evidence.INPUT, null, now, null, session.startAt()));
            case ABSENT -> List.of(new EventDraft(Actor.ME, Verb.ABSENT, target, null, new Payloads.Empty(),
                    Evidence.INPUT, null, now, null, session.startAt()));
        };
    }

    /** 화면이 고를 수 있는 원천은 자료 구간·자료 전체뿐(검증은 기록기가 한다). */
    private static List<SourceRef> sourcesOf(List<SourceInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        inputs = inputs.stream().filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(SourceInput::ref, i -> i, (x, y) -> x, java.util.TreeMap::new))
                .values().stream().toList();
        if (inputs.size() > MAX_SOURCES) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        List<SourceRef> out = new ArrayList<>();
        for (SourceInput in : inputs) {
            ObjectKind kind;
            try {
                kind = ObjectKind.valueOf(in.kind());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
            if ((kind != ObjectKind.SECTION && kind != ObjectKind.MATERIAL) || in.ref() == null || in.ref().length() > 40) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
            try {
                out.add(new SourceRef(kind, in.ref(), in.from(), in.to()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
        }
        return out;
    }

    private int revisionOf(long userId, long sessionId) {
        LearningEventMapper.OriginRow row = eventMapper.findOrigin(userId, OriginKind.CLASS_INPUT.name(), sessionId);
        return row == null ? 0 : row.currentRevision();
    }

    private List<LearningEvent> currentOutputs(long userId, long sessionId) {
        return sourceMapper.findCurrentOutputs(userId, OriginKind.CLASS_INPUT.name(), sessionId);
    }

    // ===== 회차 =====

    /** 회차를 찾거나 만든다. 루틴 행을 잠근다(일정 예외 변경과 직렬화). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClassSessionMapper.SessionRow ensureLocked(long userId, long routineId, LocalDate sourceDate) {
        Routine routine = routineMapper.findByIdAndUserIdForUpdate(routineId, userId);
        if (routine == null) {
            throw new NotFoundException(ErrorCode.ROUTINE_NOT_FOUND);
        }
        ClassSessionMapper.SessionRow existing = sessionMapper.find(userId, routineId, sourceDate);
        if (existing != null) {
            return existing;
        }
        routineReader.attachWeekdays(routine);
        RoutineException exception = exceptionMapper.findByRoutineIdAndDate(routineId, sourceDate);
        ClassSlot slot = RoutineOccurrenceService.slotOf(routine, sourceDate, exception);
        if (slot == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE); // 그날은 수업이 아니다
        }
        sessionMapper.insertIgnore(userId, slot.courseId(), routineId, sourceDate, slot.startAt(), slot.endAt());
        ClassSessionMapper.SessionRow created = sessionMapper.find(userId, routineId, sourceDate);
        if (exception != null) {
            recordException(userId, routine, exception);
        }
        return created;
    }

    /** 일정 예외를 추가·수정한 뒤(루틴 잠금 아래). 그 예외가 붙은 회차가 있으면 휴강·이동을 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exceptionChanged(long userId, long routineId, long exceptionId) {
        Routine routine = routineMapper.findByIdAndUserIdForUpdate(routineId, userId);
        RoutineException exception = exceptionMapper.findByIdAndUserId(exceptionId, userId);
        if (routine == null || exception == null) {
            return;
        }
        recordException(userId, routine, exception);
    }

    /** 일정 예외를 지우기 직전(루틴 잠금 아래). 그 예외의 표시를 내린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exceptionRemoving(long userId, long routineId, long exceptionId) {
        routineMapper.findByIdAndUserIdForUpdate(routineId, userId);
        lowerRecorded(userId, exceptionId);
    }

    /** 이미 기록된 예외 표시를 기록할 때의 과목으로 내린다(루틴의 과목을 바꿨거나 풀었어도). */
    private void lowerRecorded(long userId, long exceptionId) {
        LearningEventMapper.OriginRow row = eventMapper.findOrigin(userId, OriginKind.SCHEDULE_EXCEPTION.name(), exceptionId);
        if (row != null && row.currentRevision() > 0) {
            writer.write(new EventOrigin(userId, row.courseId(), OriginKind.SCHEDULE_EXCEPTION, exceptionId), List.of());
        }
    }

    private void recordException(long userId, Routine routine, RoutineException exception) {
        Long courseId = routine.getCourseId();
        if (courseId == null || eventMapper.countOwnedCourse(userId, courseId) != 1
                || !sameCourse(userId, exception.getRoutineExceptionId(), courseId)) {
            // 루틴의 과목을 풀었거나 바꿨다 — 옛 과목에 남긴 표시는 내린다(같은 origin의 과목은 바뀌지 않는다)
            lowerRecorded(userId, exception.getRoutineExceptionId());
            return;
        }
        ClassSessionMapper.SessionRow session = sessionMapper.find(userId, routine.getRoutineId(), exception.getExceptionDate());
        List<EventDraft> drafts = new ArrayList<>();
        if (session != null && Objects.equals(session.courseId(), courseId)) {
            SourceRef target = SourceRef.session(session.sessionId());
            if (exception.getType() == RoutineExceptionType.SKIP) {
                drafts.add(EventDraft.of(Actor.CLASS, Verb.CANCELLED, target, new Payloads.Empty(), Evidence.INPUT));
            } else if (exception.getType() == RoutineExceptionType.MOVED && exception.getMovedDate() != null) {
                ClassSlot moved = RoutineOccurrenceService.slotOf(withWeekdays(routine), exception.getExceptionDate(), exception);
                drafts.add(EventDraft.of(Actor.CLASS, Verb.SESSION_MOVED, target,
                        new Payloads.SessionMoved(exception.getMovedDate().toString(),
                                moved == null ? null : moved.startAt().toString(),
                                moved == null ? null : moved.endAt().toString()), Evidence.INPUT));
            }
        }
        writer.write(new EventOrigin(userId, courseId, OriginKind.SCHEDULE_EXCEPTION, exception.getRoutineExceptionId()),
                drafts);
    }

    private Routine withWeekdays(Routine routine) {
        if (routine.getDaysOfWeek() == null || routine.getDaysOfWeek().isEmpty()) {
            routineReader.attachWeekdays(routine);
        }
        return routine;
    }

    /** 예외 origin이 이미 다른 과목으로 기록됐으면(루틴의 과목을 바꿈) 건드리지 않는다 — origin의 과목은 바뀌지 않는다. */
    private boolean sameCourse(long userId, long exceptionId, long courseId) {
        LearningEventMapper.OriginRow row = eventMapper.findOrigin(userId, OriginKind.SCHEDULE_EXCEPTION.name(), exceptionId);
        if (row != null && row.courseId() != courseId) {
            log.info("일정 예외 이벤트: 루틴의 과목이 바뀌어 건너뜀(exceptionId={})", exceptionId);
            return false;
        }
        return true;
    }

    // ===== 설정 =====

    @Transactional
    public void setPromptEnabled(Long userId, Long courseId, boolean enabled) {
        if (sessionMapper.updatePromptEnabled(userId, courseId, enabled) == 0) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
    }

    private static String key(Long routineId, LocalDate date) {
        return routineId + "|" + date;
    }
}
