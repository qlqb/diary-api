package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventOutputs.Output;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 기록하기 전에 출력이 가리키는 것이 모두 그 사용자·그 과목의 것인지 본다(계획 §2.2, §11.3·§11.5).
 *
 * <p>잠그지 않는다 — 소유·존재는 바뀌지 않는 값만 본다. 자료 연결은 바뀔 수 있지만, 연결 해제와 경합해 "해제 직후 커밋된
 * 이벤트"가 생겨도 해제 직전에 쓴 이벤트와 같은 상태다(이벤트는 해제 뒤에도 남는 설계).
 *
 * <p><b>새 참조</b>(이 origin의 현재 판에 없던 원천)는 지금 그 과목에 연결된 자료·그 과목 토픽의 목차 열쇠여야 한다.
 * <b>기존 참조</b>(현재 판에 이미 있는 원천을 다시 쓰는 경우 — 회고 수정 등)는 소유만 본다. 그래서 연결을 끊거나 교재를 바꾼 뒤에도
 * 과거 기록의 정정이 실패하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class LearningEventValidator {

    private final LearningEventMapper mapper;

    /** 기록할 수 없는 출력. 원본 쓰기와 함께 롤백된다. */
    public static class InvalidEventException extends IllegalStateException {
        public InvalidEventException(String message) {
            super(message);
        }
    }

    /**
     * @param existing 이 origin의 현재 판 출력(없으면 빈 목록)
     */
    public void validate(EventOrigin origin, List<Output> outputs, List<Output> existing) {
        long userId = origin.userId();
        long courseId = origin.courseId();
        if (mapper.countOwnedCourse(userId, courseId) != 1) {
            throw new InvalidEventException("과목이 그 사용자의 것이 아니다");
        }
        checkOrigin(origin, outputs.isEmpty());
        if (outputs.isEmpty()) {
            return;
        }

        Set<String> known = new HashSet<>();
        for (Output o : existing) {
            known.add(o.object().identity());
            o.payloadSources().forEach(s -> known.add(s.identity()));
        }

        // 해결은 막힘의 원천을 그대로 잇는다 — 막힘이 가리키던 원천은 기존 참조다(연결을 끊은 뒤에도 해결을 저장할 수 있게).
        Set<Long> resolvedStucks = new LinkedHashSet<>();
        outputs.forEach(o -> resolvedStucks.addAll(o.liveStuckRefs()));
        if (!resolvedStucks.isEmpty()) {
            for (LearningEvent e : mapper.findEventsByIds(userId, resolvedStucks)) {
                if (Objects.equals(e.getCourseId(), courseId)) {
                    known.add(e.getObjectKind() + "|" + e.getObjectRef());
                }
            }
        }

        List<SourceRef> refs = new ArrayList<>();
        Set<Long> topicIds = new LinkedHashSet<>();
        Set<Long> eventIds = new LinkedHashSet<>();
        Set<Long> liveStuck = new LinkedHashSet<>();
        for (Output o : outputs) {
            refs.add(o.object());
            refs.addAll(o.payloadSources());
            if (o.topicId() != null) {
                topicIds.add(o.topicId());
            }
            eventIds.addAll(o.eventRefs());
            liveStuck.addAll(o.liveStuckRefs());
            if ((Verb.valueOf(o.verb()) == Verb.RETRACTED || Verb.valueOf(o.verb()) == Verb.SUPERSEDES)
                    && o.eventRefs().isEmpty()) {
                throw new InvalidEventException("철회·대체가 대상 이벤트를 가리키지 않는다");
            }
        }

        if (!topicIds.isEmpty() && mapper.findTopicIdsInCourse(userId, courseId, topicIds).size() != topicIds.size()) {
            throw new InvalidEventException("그 과목의 토픽이 아니다");
        }
        checkSources(userId, courseId, refs, known);
        checkEvents(userId, courseId, eventIds, liveStuck);
    }

    private void checkOrigin(EventOrigin origin, boolean noOutputs) {
        if (origin.kind() == OriginKind.TEST) {
            return;
        }
        LearningEventMapper.OriginOwner owner = mapper.findOriginOwner(origin.kind().name(), origin.id());
        if (owner == null) {
            // 원본을 지우기 직전에 출력 0 판을 쓰는 경로(일정 예외 삭제)는 원본이 아직 있다. 원본이 이미 없으면 내릴 것만 허용한다.
            if (noOutputs) {
                return;
            }
            throw new InvalidEventException("원본이 없다: " + origin.kind());
        }
        if (!Objects.equals(owner.userId(), origin.userId()) || !Objects.equals(owner.courseId(), origin.courseId())) {
            throw new InvalidEventException("원본의 사용자·과목이 다르다: " + origin.kind());
        }
    }

    private void checkSources(long userId, long courseId, List<SourceRef> refs, Set<String> known) {
        Map<Long, List<SourceRef>> sections = new HashMap<>();
        Map<Long, List<SourceRef>> materials = new HashMap<>();
        List<SourceRef> toc = new ArrayList<>();
        java.util.Set<Long> sessions = new LinkedHashSet<>();
        for (SourceRef r : refs) {
            switch (r.kind()) {
                case COURSE -> {
                    if (!SourceRef.COURSE_REF.equals(r.ref()) || r.from() != null || r.to() != null) {
                        throw new InvalidEventException("과목 원천 형식");
                    }
                }
                case SECTION -> sections.computeIfAbsent(idOf(r, "s"), k -> new ArrayList<>()).add(r);
                case MATERIAL -> materials.computeIfAbsent(idOf(r, "m"), k -> new ArrayList<>()).add(r);
                case TOC_ENTRY -> toc.add(r);
                case SESSION -> {
                    if (r.from() != null || r.to() != null) {
                        throw new InvalidEventException("수업 회차에는 범위를 두지 않는다");
                    }
                    sessions.add(idOf(r, "c"));
                }
                default -> throw new InvalidEventException("아직 기록할 수 없는 원천 종류: " + r.kind());
            }
        }
        if (!sessions.isEmpty() && mapper.findSessionIds(userId, courseId, sessions).size() != sessions.size()) {
            throw new InvalidEventException("그 과목의 수업 회차가 아니다");
        }
        if (!sections.isEmpty()) {
            Map<Long, LearningEventMapper.SectionRow> found = new HashMap<>();
            mapper.findSections(userId, courseId, sections.keySet()).forEach(s -> found.put(s.sectionId(), s));
            for (Map.Entry<Long, List<SourceRef>> e : sections.entrySet()) {
                LearningEventMapper.SectionRow row = found.get(e.getKey());
                if (row == null) {
                    throw new InvalidEventException("그 사용자의 자료 구간이 아니다");
                }
                for (SourceRef r : e.getValue()) {
                    if (!Boolean.TRUE.equals(row.linked()) && !known.contains(r.identity())) {
                        throw new InvalidEventException("그 과목에 연결된 자료의 구간이 아니다");
                    }
                    checkRange(r, row);
                }
            }
        }
        if (!materials.isEmpty()) {
            Map<Long, LearningEventMapper.MaterialRow> found = new HashMap<>();
            mapper.findMaterials(userId, courseId, materials.keySet()).forEach(m -> found.put(m.materialId(), m));
            for (Map.Entry<Long, List<SourceRef>> e : materials.entrySet()) {
                LearningEventMapper.MaterialRow row = found.get(e.getKey());
                if (row == null) {
                    throw new InvalidEventException("그 사용자의 자료가 아니다");
                }
                for (SourceRef r : e.getValue()) {
                    if (!Boolean.TRUE.equals(row.linked()) && !known.contains(r.identity())) {
                        throw new InvalidEventException("그 과목에 연결된 자료가 아니다");
                    }
                    if (r.from() != null || r.to() != null) {
                        throw new InvalidEventException("자료 전체에는 범위를 두지 않는다");
                    }
                }
            }
        }
        if (!toc.isEmpty()) {
            Set<Long> refIds = new LinkedHashSet<>();
            for (SourceRef r : toc) {
                SourceRef.TocKey key = r.tocKey();
                if (key == null || r.from() != null || r.to() != null) {
                    throw new InvalidEventException("목차 항목 원천 형식");
                }
                refIds.add(key.bookRefId());
            }
            if (mapper.findOwnedBookRefs(userId, refIds).size() != refIds.size()) {
                throw new InvalidEventException("그 사용자의 교재가 아니다");
            }
            for (SourceRef r : toc) {
                if (known.contains(r.identity())) {
                    continue;
                }
                SourceRef.TocKey key = r.tocKey();
                if (mapper.countTocEntryInCourse(userId, courseId, key.bookRefId(), key.keyHash(), key.keyLine()) == 0) {
                    throw new InvalidEventException("그 과목 교재 목차의 항목이 아니다");
                }
            }
        }
    }

    private static void checkRange(SourceRef r, LearningEventMapper.SectionRow row) {
        if (r.from() == null && r.to() == null) {
            return;
        }
        if (r.from() == null || r.to() == null || r.from() > r.to()) {
            throw new InvalidEventException("구간 범위 형식");
        }
        Integer lo = row.printedPageStart();
        Integer hi = row.printedPageEnd();
        if (lo == null || hi == null || r.from() < lo || r.to() > hi) {
            throw new InvalidEventException("구간의 쪽 범위 밖");
        }
    }

    private void checkEvents(long userId, long courseId, Set<Long> eventIds, Set<Long> liveStuck) {
        if (eventIds.isEmpty() && liveStuck.isEmpty()) {
            return;
        }
        Set<Long> all = new LinkedHashSet<>(eventIds);
        all.addAll(liveStuck);
        Map<Long, LearningEventMapper.EventRefRow> found = new HashMap<>();
        mapper.findEventRefs(userId, all).forEach(e -> found.put(e.eventId(), e));
        for (Long id : all) {
            LearningEventMapper.EventRefRow row = found.get(id);
            if (row == null || !Objects.equals(row.courseId(), courseId)) {
                throw new InvalidEventException("그 과목의 이벤트가 아니다");
            }
            if (liveStuck.contains(id) && (!Boolean.TRUE.equals(row.live()) || !Verb.STUCK.name().equals(row.verb()))) {
                throw new InvalidEventException("살아 있는 막힘이 아니다");
            }
        }
    }

    private static long idOf(SourceRef r, String prefix) {
        Long id = r.numericId();
        if (id == null || !r.ref().startsWith(prefix + ":") || id <= 0) {
            throw new InvalidEventException("원천 참조 형식: " + r.kind());
        }
        return id;
    }
}
