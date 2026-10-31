package com.jungwoo.project.memo.learning.domain;

/** SKIP은 학습 진도 상태가 아니라 "이번 계획에서는 굳이 하지 않아도 된다"는 추천 판단이다. */
public enum ActivityType {
    NEW_LEARNING,
    REVIEW,
    CONTINUE,
    SKIP
}
