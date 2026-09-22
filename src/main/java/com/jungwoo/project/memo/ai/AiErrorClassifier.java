package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;

import java.util.concurrent.TimeoutException;

/**
 * 스트리밍 중 발생한 예외를 사용자에게 노출할 ErrorCode로 분류한다.
 *
 * 429(쿼터/결제)는 코드 버그가 아니라 계정 상태 문제이므로 AI_GENERATION_FAILED로 뭉뚱그리지
 * 않고 별도 메시지를 보여준다. 원문 예외 메시지나 스택은 그대로 클라이언트에 내보내지 않는다 —
 * 여기서 분류한 ErrorCode의 고정 메시지만 나간다.
 */
final class AiErrorClassifier {

    private AiErrorClassifier() {
    }

    /** ai_usage_logs.result_status로 남길 값. 타임아웃과 그 외 오류를 구분한다. */
    static UsageResultStatus classifyUsageStatus(Throwable throwable) {
        return isTimeout(throwable) ? UsageResultStatus.TIMEOUT : UsageResultStatus.FAILED;
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return false;
    }

    static ErrorCode classify(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (isQuotaOrRateLimit(current)) {
                return ErrorCode.AI_QUOTA_EXCEEDED;
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return ErrorCode.AI_GENERATION_FAILED;
    }

    private static boolean isQuotaOrRateLimit(Throwable e) {
        String className = e.getClass().getName();
        if (className.contains("WebClientResponseException")) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("429")) {
                return true;
            }
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("429")
                || lower.contains("insufficient_quota")
                || lower.contains("rate limit")
                || lower.contains("quota");
    }
}
