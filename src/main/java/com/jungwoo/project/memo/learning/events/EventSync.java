package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 과목 하나의 한 종류 origin들을 "원본 행의 지금 상태"에 맞춘다. 바뀐 것만 기록기에 넘긴다(같으면 판을 올리지 않는다).
 * 호출자는 그 과목의 쓰기를 직렬화하는 잠금(과목 행)을 잡고, <b>READ_COMMITTED</b> 트랜잭션에서 부른다 — 잠금을 기다린 뒤의 일반 읽기가
 * 최신 커밋을 보게(옛 스냅샷이면 다른 요청이 먼저 쓴 값을 덮는다). 잠금 읽기는 쓰지 않는다 — 빈 범위의 간격 잠금이 다른 과목의 첫 origin
 * 삽입과 교착할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class EventSync {

    private final EventSourceMapper sourceMapper;
    private final LearningEventWriter writer;
    private final ObjectMapper objectMapper;

    /** 과목의 그 종류 origin의 지금 출력과 판이 있는 origin들. */
    public Snapshot snapshot(long userId, long courseId, OriginKind kind) {
        Map<Long, List<LearningEvent>> outputs = new HashMap<>();
        for (LearningEvent e : sourceMapper.findCurrentOutputsOfCourse(userId, courseId, kind.name())) {
            outputs.computeIfAbsent(e.getOriginId(), k -> new ArrayList<>()).add(e);
        }
        return new Snapshot(outputs, new HashSet<>(sourceMapper.findOriginIdsOfCourse(userId, courseId, kind.name())));
    }

    public record Snapshot(Map<Long, List<LearningEvent>> outputs, Set<Long> written) {
        public List<LearningEvent> of(long originId) {
            return outputs.getOrDefault(originId, List.of());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(EventOrigin origin, List<EventDraft> drafts, Snapshot snapshot) {
        boolean written = snapshot.written().contains(origin.id());
        if (drafts.isEmpty() && !written) {
            return; // 내릴 것이 없다(과목 잠금 아래라 그사이 판이 생기지 않는다)
        }
        if (written && EventOutputs.sameOutputs(EventOutputs.normalize(drafts, objectMapper),
                EventOutputs.fromRows(snapshot.of(origin.id()), objectMapper))) {
            return;
        }
        writer.write(origin, drafts);
    }
}
