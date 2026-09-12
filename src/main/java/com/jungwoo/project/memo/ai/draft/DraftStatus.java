package com.jungwoo.project.memo.ai.draft;

/** OPEN → PROMOTED(proposal이 만들어짐) 또는 CANCELLED(모델 CANCEL op / 대화 ARCHIVED). */
public enum DraftStatus {
    OPEN,
    PROMOTED,
    CANCELLED
}
