package com.jungwoo.project.memo.execution.domain;

/**
 * 실행 조각이 완료됐을 때 발행하는 이벤트. execution 패키지는 이 조각이 학습 topic과
 * 연결돼 있는지만 알리고, 그 정보를 어떻게 쓸지(topic_progress 갱신 등)는 학습 쪽 리스너의
 * 책임이다 — execution이 learning 패키지를 직접 알 필요가 없도록 이벤트로 분리한다.
 */
public record ExecutionItemCompletedEvent(Long executionItemId, Long userId, Long topicId) {
}
