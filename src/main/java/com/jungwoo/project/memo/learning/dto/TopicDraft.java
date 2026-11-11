package com.jungwoo.project.memo.learning.dto;

import java.util.List;

/**
 * Material Agent 분석 결과(또는 그 수정본)에서 확정 전 단계의 topic 노드 하나.
 * TopicService.applyAnalyzedTopics()의 입력 전용 — course_topics 확정 삽입에만 쓰인다.
 */
public record TopicDraft(String title, String sourceType, String sourceLocator, List<TopicDraft> children) {
}
