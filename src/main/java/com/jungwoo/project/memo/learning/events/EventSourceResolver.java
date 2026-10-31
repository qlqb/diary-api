package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.course.textbook.TextbookRefService;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.plan.PlanItemDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원본(실행 기록·기억 행·토픽)이 가리키는 원천을 찾는다(계획 §3.1, §11.1).
 *
 * <ul>
 *   <li><b>LIVE</b>(cutover 뒤 원본): 직접 가리킨 원천이 없으면 토픽의 목차 열쇠·연결 구간을 근사(APPROX)로 쓴다.</li>
 *   <li><b>HISTORICAL</b>(cutover 전 원본 — 백필과 같은 규칙): 직접 가리킨 원천만. 없으면 빈 목록(이벤트를 만들지 않는다).</li>
 * </ul>
 * 기록기의 검증을 통과할 원천만 낸다 — 구간은 그 사용자의 것이고 지금 그 과목에 연결돼 있거나(새 참조) 이미 이 원본의 출력에 있던
 * 것(기존 참조)이어야 한다. 검증 실패는 원본 쓰기까지 롤백하므로, 여기서 미리 거른다.
 */
@Component
@RequiredArgsConstructor
public class EventSourceResolver {

    static final int MAX_SECTIONS = 20;
    static final BigDecimal APPROX_CONFIDENCE = new BigDecimal("0.40");

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final TextbookRefService refService;
    private final PlanItemDetailService planItemDetailService;

    public enum Mode { LIVE, HISTORICAL }

    /**
     * 찾은 원천 하나.
     *
     * @param evidence   이 원천의 근거(APPROX면 근사). null이면 호출자의 근거를 쓴다
     * @param confidence 근사일 때의 확신도
     */
    public record Resolved(SourceRef ref, Evidence evidence, BigDecimal confidence) {
        static Resolved direct(SourceRef ref) {
            return new Resolved(ref, null, null);
        }

        static Resolved approx(SourceRef ref) {
            return new Resolved(ref, Evidence.APPROX, APPROX_CONFIDENCE);
        }

        public boolean approximate() {
            return evidence == Evidence.APPROX;
        }
    }

    /** 실행 조각이 인용한 구간·목차 토픽(LOGGED). 인용이 없으면 LIVE에서만 항목의 토픽으로 근사. */
    public List<Resolved> forExecution(long userId, long courseId, long executionItemId, Long topicId, Mode mode,
                                       Set<String> known) {
        PlanItemDetailService.Citations cited = planItemDetailService.citationsOfExecutionItem(userId, executionItemId);
        List<Resolved> out = new ArrayList<>();
        for (Long sectionId : usableSections(userId, courseId, cited.sectionIds(), known)) {
            out.add(Resolved.direct(SourceRef.section(sectionId)));
        }
        for (SourceRef toc : tocEntries(userId, courseId, cited.topicIds())) {
            out.add(Resolved.direct(toc));
        }
        if (!out.isEmpty() || mode == Mode.HISTORICAL || topicId == null) {
            return out;
        }
        return approxOfTopic(userId, courseId, topicId, known);
    }

    /**
     * 기억·점검이 가리킨 원천. 구간·사진·목차 토픽은 직접(그 기억의 근거 그대로), 그 밖의 토픽은 LIVE에서만 연결 구간으로 근사.
     */
    public List<Resolved> forMemory(long userId, long courseId, Long sectionId, Long photoMaterialId, Long topicId,
                                    Mode mode, Set<String> known) {
        List<Resolved> out = new ArrayList<>();
        if (sectionId != null) {
            usableSections(userId, courseId, List.of(sectionId), known)
                    .forEach(id -> out.add(Resolved.direct(SourceRef.section(id))));
        }
        if (out.isEmpty() && photoMaterialId != null) {
            Long photoSection = sourceMapper.findPhotoSection(userId, photoMaterialId);
            if (photoSection != null) {
                usableSections(userId, courseId, List.of(photoSection), known)
                        .forEach(id -> out.add(Resolved.direct(SourceRef.section(id))));
            }
        }
        if (out.isEmpty() && topicId != null) {
            tocEntries(userId, courseId, List.of(topicId)).forEach(t -> out.add(Resolved.direct(t)));
            if (out.isEmpty() && mode == Mode.LIVE) {
                out.addAll(approxOfTopic(userId, courseId, topicId, known));
            }
        }
        return out;
    }

    /** 토픽(범위 제외·정리안 CLASS·진도 표식). 목차 토픽이면 직접, 아니면 LIVE에서만 연결 구간 근사. */
    public List<Resolved> forTopic(long userId, long courseId, long topicId, Mode mode, Set<String> known) {
        List<Resolved> out = new ArrayList<>();
        tocEntries(userId, courseId, List.of(topicId)).forEach(t -> out.add(Resolved.direct(t)));
        if (out.isEmpty() && mode == Mode.LIVE) {
            out.addAll(approxOfTopic(userId, courseId, topicId, known));
        }
        return out;
    }

    private List<Resolved> approxOfTopic(long userId, long courseId, long topicId, Set<String> known) {
        List<Resolved> out = new ArrayList<>();
        for (SourceRef toc : tocEntries(userId, courseId, List.of(topicId))) {
            out.add(Resolved.approx(toc));
        }
        if (!out.isEmpty()) {
            return out;
        }
        List<Long> linked = sourceMapper.findTopicSections(userId, courseId, List.of(topicId)).stream()
                .map(EventSourceMapper.TopicSection::sectionId).toList();
        for (Long id : usableSections(userId, courseId, linked, known)) {
            out.add(Resolved.approx(SourceRef.section(id)));
        }
        return out;
    }

    /** 목차 토픽(열쇠 SET, 교재 키 있음)의 목차 항목. 교재 식별자는 없으면 만든다. */
    List<SourceRef> tocEntries(long userId, long courseId, Collection<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return List.of();
        }
        List<SourceRef> out = new ArrayList<>();
        for (EventSourceMapper.TopicRow t : sourceMapper.findTopics(userId, courseId, topicIds)) {
            if (!"SET".equals(t.tocKeyState()) || t.tocKeyHash() == null || t.tocKeyLine() == null
                    || t.sourceTextbookKey() == null) {
                continue;
            }
            Long ref = refService.refFor(userId, t.sourceTextbookKey());
            if (ref != null) {
                out.add(SourceRef.tocEntry(ref, t.tocKeyHash(), t.tocKeyLine()));
            }
        }
        return out;
    }

    /** 기록기가 받아들일 구간만(그 사용자의 것 + 지금 연결 또는 기존 참조), 순서 유지, 상한. */
    List<Long> usableSections(long userId, long courseId, Collection<Long> ids, Set<String> known) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> wanted = new LinkedHashSet<>(ids);
        Map<Long, LearningEventMapper.SectionRow> found = new HashMap<>();
        eventMapper.findSections(userId, courseId, wanted).forEach(r -> found.put(r.sectionId(), r));
        List<Long> out = new ArrayList<>();
        for (Long id : wanted) {
            LearningEventMapper.SectionRow row = found.get(id);
            if (row == null) {
                continue;
            }
            if (Boolean.TRUE.equals(row.linked()) || known.contains(SourceRef.section(id).identity())) {
                out.add(id);
            }
            if (out.size() >= MAX_SECTIONS) {
                break;
            }
        }
        return out;
    }

    /** origin의 지금 출력이 가리키는 원천(기존 참조 집합). */
    public Set<String> known(long userId, EventVocabulary.OriginKind kind, long originId) {
        Set<String> known = new LinkedHashSet<>();
        for (LearningEvent e : sourceMapper.findCurrentOutputs(userId, kind.name(), originId)) {
            known.add(e.getObjectKind() + "|" + e.getObjectRef());
        }
        return known;
    }
}
