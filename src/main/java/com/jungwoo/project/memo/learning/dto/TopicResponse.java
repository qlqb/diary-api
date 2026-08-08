package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TopicResponse {

    private Long topicId;
    private Long parentTopicId;
    private String title;
    private int orderIndex;
    private TopicSourceType sourceType;
    private String sourceLocator;
    private TopicProgressStatus progressStatus;
    private LocalDateTime lastStudiedAt;
    private LocalDateTime lastReviewedAt;
    private int reviewCount;
    private List<TopicResponse> children;

    public static TopicResponse of(CourseTopic topic, TopicProgress progress, List<TopicResponse> children) {
        return TopicResponse.builder()
                .topicId(topic.getTopicId())
                .parentTopicId(topic.getParentTopicId())
                .title(topic.getTitle())
                .orderIndex(topic.getOrderIndex())
                .sourceType(topic.getSourceType())
                .sourceLocator(topic.getSourceLocator())
                .progressStatus(progress != null ? progress.getStatus() : TopicProgressStatus.NOT_STARTED)
                .lastStudiedAt(progress != null ? progress.getLastStudiedAt() : null)
                .lastReviewedAt(progress != null ? progress.getLastReviewedAt() : null)
                .reviewCount(progress != null && progress.getReviewCount() != null ? progress.getReviewCount() : 0)
                .children(children)
                .build();
    }
}
