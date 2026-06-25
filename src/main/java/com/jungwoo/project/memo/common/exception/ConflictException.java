package com.jungwoo.project.memo.common.exception;

// ===== 409 Conflict =====

/**
 * 중복된 리소스가 존재할 때 발생
 */
public class ConflictException extends BusinessException {
    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }
}