package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 과목의 살아 있는 학습 이벤트(설계 20번 §6.4 1~2단계). 거르기는 SQL에서 한다(쪽 나눔 전에).
 *
 * <ol>
 *   <li>origin마다 지금 판의 출력만(옛 판·출력 0 판으로 내려간 것은 빠진다).</li>
 *   <li>살아 있는 RETRACTED가 가리키는 이벤트와 RETRACTED 자신은 빠진다(철회의 철회는 없다).</li>
 *   <li>살아 있는 SUPERSEDES의 target은 빠진다 — 사슬의 마지막만 남는다.</li>
 * </ol>
 * 응답의 payload는 Map이다 — HTTP 변환기(Jackson 3)가 Jackson 2 트리를 그대로 쓰지 못한다.
 * "같은 대상의 현재 값"(claim 순서)은 2단계 뷰가 정한다.
 */
@Service
@RequiredArgsConstructor
public class LearningEventLog {

    public static final int MAX_LIMIT = 200;

    private final LearningEventMapper mapper;
    private final ObjectMapper objectMapper;

    public record EventView(Long eventId, String originKind, Long originId, String actor, String verb,
                            String objectKind, String objectRef, Integer objectFrom, Integer objectTo, Long topicId,
                            Map<String, Object> payload, String evidence, BigDecimal confidence, LocalDateTime claimAt,
                            Long claimSeq, LocalDateTime occurredAt, LocalDateTime recordedAt) {
    }

    @Transactional(readOnly = true)
    public List<EventView> live(long userId, long courseId, long afterId, int limit) {
        if (mapper.countOwnedCourse(userId, courseId) != 1) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        return mapper.findLive(userId, courseId, Math.max(0, afterId), size).stream().map(this::view).toList();
    }

    /** 살아 있는 이벤트 전부(2단계 뷰·테스트용). */
    @Transactional(readOnly = true)
    public List<LearningEvent> liveEvents(long userId, long courseId) {
        List<LearningEvent> all = new ArrayList<>();
        long after = 0;
        while (true) {
            List<LearningEvent> page = mapper.findLive(userId, courseId, after, 1000);
            all.addAll(page);
            if (page.size() < 1000) {
                return all;
            }
            after = page.get(page.size() - 1).getEventId();
        }
    }

    private EventView view(LearningEvent e) {
        return new EventView(e.getEventId(), e.getOriginKind(), e.getOriginId(), e.getActor(), e.getVerb(),
                e.getObjectKind(), e.getObjectRef(), e.getObjectFrom(), e.getObjectTo(), e.getTopicId(),
                parse(e.getPayload()), e.getEvidence(), e.getConfidence(), e.getClaimAt(), e.getClaimSeq(),
                e.getOccurredAt(), e.getRecordedAt());
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("저장된 payload를 읽지 못했다", ex);
        }
    }
}
