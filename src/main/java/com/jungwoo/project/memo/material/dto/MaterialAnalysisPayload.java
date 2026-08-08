package com.jungwoo.project.memo.material.dto;

import java.util.List;

/**
 * Material Agent가 만드는 구조화 분석 결과. analysis_json/edited_json에 그대로 직렬화해 저장하고,
 * apply() 시점에만 course_topics로 확정 삽입된다.
 *
 * topics의 각 노드는 sourceType으로 "원문에 실제 있던 항목(SOURCE)"과 "AI가 학습 편의를 위해
 * 세분화한 항목(AI_DERIVED)"을 반드시 구분한다. 원문에 목차 근거가 없으면 topics는 빈 배열이고
 * summary에 그 사실을 설명한다 — 절대 지어내지 않는다.
 */
public record MaterialAnalysisPayload(
        String summary,
        CourseFields courseFields,
        List<KeyDate> keyDates,
        List<TopicNode> topics
) {
    public record CourseFields(
            String textbookTitle,
            String textbookAuthor,
            String textbookPublisher,
            String textbookIsbn
    ) {
    }

    public record KeyDate(String title, String date, String description) {
    }

    public record TopicNode(
            String title,
            /** "SOURCE" 또는 "AI_DERIVED" */
            String sourceType,
            String sourceLocator,
            List<TopicNode> children
    ) {
    }
}
