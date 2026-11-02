package com.jungwoo.project.memo.common.exception;

// ===== 503 Service Unavailable =====

/**
 * 의존 서비스(AI 등)가 설정되지 않았거나 응답할 수 없을 때 발생
 */
public class ServiceUnavailableException extends BusinessException {
    public ServiceUnavailableException(ErrorCode errorCode) {
        super(errorCode);
    }
}
