package com.jungwoo.project.memo.material.domain;

/**
 * material_analysis_jobs.status.
 *
 * <p>PARTIAL은 실패가 아니다 — 읽은 범위까지의 결과는 저장돼 있고, 남은 범위는 checkpoint에
 * 적혀 있다. UNAVAILABLE은 서비스 쪽 문제(인증·미설정)라 재시도해도 소용없는 상태다.
 */
public enum AnalysisJobStatus {
    QUEUED,
    RUNNING,
    DONE,
    PARTIAL,
    FAILED,
    UNAVAILABLE,
    PAUSED,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == PARTIAL || this == FAILED || this == UNAVAILABLE || this == CANCELLED;
    }

    /** 결과를 계획·화면에서 써도 되는 상태. */
    public boolean hasUsableResult() {
        return this == DONE || this == PARTIAL;
    }
}
