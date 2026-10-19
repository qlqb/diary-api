package com.jungwoo.project.memo.learning.tidy.domain;

/**
 * project_tidy_jobs.status — 정리안을 <b>만드는</b> 작업의 상태다. 정리안 자체의 상태가 아니다.
 *
 * <p>CANCELLED는 사용자가 버렸거나 더 최신 요청이 들어와 이 작업의 결과가 필요 없어진 것이다.
 * 늦게 끝난 작업이 결과를 쓰려 하면 세대·임대 대조에서 막힌다.
 */
public enum TidyJobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    /** 모델이 설정되지 않았거나 인증이 막혔다. 다시 눌러도 같다. */
    UNAVAILABLE,
    CANCELLED;

    public boolean isOpen() {
        return this == QUEUED || this == RUNNING;
    }
}
