package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Agent가 만드는 구조화된 "다음 학습" 후보. study_recommendations 테이블과 1:1 대응.
 *
 * WHAT(무엇을 공부할지)만 담당한다 — 시간 배치(WHEN)는 Planning Agent의 몫이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyRecommendation {

    private Long recommendationId;

    private Long userId;

    private Long courseId;

    private Long topicId;

    /** Planning Agent가 이 추천으로 만든 ai_proposals 행. 아직 계획으로 안 넘겼으면 null. */
    private Long proposalId;

    private ActivityType activityType;

    private Integer recommendedMinutesMin;

    private Integer recommendedMinutesIdeal;

    private RecommendationPriority priority;

    private String reason;

    private RecommendationStatus status;

    private Long sourceConversationId;

    private Long sourceMessageId;

    private LocalDateTime createdAt;
}
