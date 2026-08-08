package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.ActivityType;
import com.jungwoo.project.memo.learning.domain.RecommendationPriority;
import com.jungwoo.project.memo.learning.domain.RecommendationStatus;
import com.jungwoo.project.memo.learning.domain.StudyRecommendation;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class StudyRecommendationResponse {

    private Long recommendationId;
    private Long courseId;
    private Long topicId;
    private String topicTitle;
    private ActivityType activityType;
    private Integer recommendedMinutesMin;
    private Integer recommendedMinutesIdeal;
    private RecommendationPriority priority;
    private String reason;
    private RecommendationStatus status;
    private LocalDateTime createdAt;

    public static StudyRecommendationResponse of(StudyRecommendation r, String topicTitle) {
        return StudyRecommendationResponse.builder()
                .recommendationId(r.getRecommendationId())
                .courseId(r.getCourseId())
                .topicId(r.getTopicId())
                .topicTitle(topicTitle)
                .activityType(r.getActivityType())
                .recommendedMinutesMin(r.getRecommendedMinutesMin())
                .recommendedMinutesIdeal(r.getRecommendedMinutesIdeal())
                .priority(r.getPriority())
                .reason(r.getReason())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
