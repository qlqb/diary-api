package com.jungwoo.project.memo.common.exception;

// ===== 429 Too Many Requests =====

/**
 * 사용자 호출 한도를 넘었을 때 발생 (AI 사용량 한도 등)
 */
public class TooManyRequestsException extends BusinessException {
    public TooManyRequestsException(ErrorCode errorCode) {
        super(errorCode);
    }
}
