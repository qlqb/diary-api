package com.jungwoo.project.memo.learning.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 실시간 이중 기록을 처음 켠 시각(cutover, 계획 §6·§11.1). 서버가 처음 뜰 때 한 번 기록하고 다시 바꾸지 않는다.
 *
 * <p>cutover 이전에 만든 원본을 실시간 경로가 처음 만지면 <b>백필 규칙</b>(직접 가리킨 원천만, 근사 없음)으로 첫 판을 만든다 —
 * 실시간이 먼저 하든 백필이 먼저 하든 결과가 같다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class EventCutover implements ApplicationRunner {

    private final LearningEventMetaMapper metaMapper;
    private volatile LocalDateTime cutover;

    @Override
    public void run(ApplicationArguments args) {
        try {
            at();
        } catch (Exception e) {
            log.warn("학습 이벤트 전환 시각 기록 실패: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** 전환 시각. 없으면 지금으로 기록한다. */
    public LocalDateTime at() {
        LocalDateTime c = cutover;
        if (c != null) {
            return c;
        }
        String stored = metaMapper.find(LearningEventMetaMapper.CUTOVER_AT);
        if (stored == null) {
            metaMapper.insertIgnore(LearningEventMetaMapper.CUTOVER_AT, LocalDateTime.now().withNano(0).toString());
            stored = metaMapper.find(LearningEventMetaMapper.CUTOVER_AT);
        }
        try {
            c = LocalDateTime.parse(stored);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalStateException("cutover_at 형식", e);
        }
        cutover = c;
        return c;
    }

    /** 이 원본은 전환 전에 만들어졌나(백필 규칙 대상). 생성 시각을 모르면 전환 전으로 본다. */
    public boolean historical(LocalDateTime createdAt) {
        return createdAt == null || createdAt.isBefore(at());
    }
}
