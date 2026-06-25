package com.jungwoo.project.memo.common.exception;

// ===== 404 Not Found =====

/**
 * 리소스를 찾을 수 없을 때 발생 (기존 NotFoundException)
 */
public class NotFoundException extends BusinessException {
    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}