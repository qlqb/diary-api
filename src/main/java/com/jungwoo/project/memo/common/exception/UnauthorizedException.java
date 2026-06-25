package com.jungwoo.project.memo.common.exception;

// ===== 401 Unauthorized =====
/**
 * 인증되지 않은 사용자가 접근할 때 발생
 */
public class UnauthorizedException extends BusinessException {
    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }
}