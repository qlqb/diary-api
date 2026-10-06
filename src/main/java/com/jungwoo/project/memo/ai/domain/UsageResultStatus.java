package com.jungwoo.project.memo.ai.domain;

/** ai_usage_logs.result_status 체크 제약과 1:1 대응한다. */
public enum UsageResultStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT
}
