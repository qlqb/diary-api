package com.jungwoo.project.memo.common.exception;

// ===== 403 Forbidden =====

/**
 * 권한이 없는 리소스에 접근할 때 발생
 */
public class ForbiddenException extends BusinessException {
    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }
}