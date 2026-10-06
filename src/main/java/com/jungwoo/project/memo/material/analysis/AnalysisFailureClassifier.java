package com.jungwoo.project.memo.material.analysis;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 모델 호출 실패를 재시도 정책이 다른 네 가지로 나눈다.
 *
 * <p>기존 {@code AiErrorClassifier}는 상담 응답 코드(429/그 외)만 구분한다. 백그라운드 작업은
 * "다시 해도 소용없는 것"(인증)과 "잠시 뒤 다시"(한도·타임아웃)와 "몇 번 더"(그 외)를 갈라야
 * 무한 재시도가 되지 않는다. 메시지 문자열로 판단하는 것은 정확하지 않지만, 상담 경로도 같은
 * 방식이고 여기서는 보수적으로(모르면 TRANSIENT) 처리한다.
 */
public final class AnalysisFailureClassifier {

    private AnalysisFailureClassifier() {
    }

    public enum Kind {
        /** 401/403, invalid api key — 전역 쿨다운, 작업은 UNAVAILABLE. */
        AUTH,
        /** 429, quota — 지수 백오프로 뒤로 미룬다(시도 횟수를 소모하지 않는다). */
        RATE_LIMIT,
        /** 타임아웃·5xx·네트워크 — 시도 횟수 안에서 재시도. */
        TRANSIENT,
        /** 응답은 왔는데 구조를 못 읽음 — 한 번 더 시도 후 FAILED. */
        BAD_OUTPUT
    }

    public static Kind classify(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 10) {
            if (cursor instanceof TimeoutException) {
                return Kind.TRANSIENT;
            }
            String message = String.valueOf(cursor.getMessage()).toLowerCase(Locale.ROOT);
            String type = cursor.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (message.contains("401") || message.contains("403") || message.contains("invalid_api_key")
                    || message.contains("incorrect api key") || message.contains("unauthorized")
                    || message.contains("permission denied") || type.contains("unauthorized")
                    || type.contains("forbidden")) {
                return Kind.AUTH;
            }
            if (message.contains("429") || message.contains("rate limit") || message.contains("insufficient_quota")
                    || message.contains("quota") || type.contains("toomanyrequests")) {
                return Kind.RATE_LIMIT;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return Kind.TRANSIENT;
    }
}
