package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** topic_progress 테이블과 1:1 대응. (user_id, topic_id) 유니크. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicProgress {

    private Long topicProgressId;

    private Long userId;

    private Long topicId;

    private TopicProgressStatus status;

    private LocalDateTime lastStudiedAt;

    private LocalDateTime lastReviewedAt;

    private Integer reviewCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
