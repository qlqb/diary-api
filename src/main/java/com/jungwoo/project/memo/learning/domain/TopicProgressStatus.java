package com.jungwoo.project.memo.learning.domain;

/**
 * topic별 학습 진행 상태. 완료 체크를 "완전히 이해했다"로 해석하지 않기 위해 세 단계로만
 * 단순하게 둔다 — LEARNED도 "이해 확정"이 아니라 "한 번은 마쳤다"는 뜻이다.
 */
public enum TopicProgressStatus {
    NOT_STARTED,
    IN_PROGRESS,
    LEARNED
}
