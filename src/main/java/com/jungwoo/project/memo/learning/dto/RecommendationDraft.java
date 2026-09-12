package com.jungwoo.project.memo.learning.dto;

/** Learning Agent가 만드는 구조화된 추천 원본(파싱 전용). */
public record RecommendationDraft(
        Long topicId,
        String activityType,
        Integer recommendedMinutesMin,
        Integer recommendedMinutesIdeal,
        String priority,
        String reason
) {
}
