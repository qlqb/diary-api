package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** topic_learning_events 테이블과 1:1 대응. topic_progress 상태가 왜 바뀌었는지의 이력. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicLearningEvent {

    private Long eventId;

    private Long userId;

    private Long topicId;

    private Long executionItemId;

    private LearningEventType eventType;

    private TopicProgressStatus fromStatus;

    private TopicProgressStatus toStatus;

    private String note;

    private LocalDateTime createdAt;
}
