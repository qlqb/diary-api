package com.jungwoo.project.memo.common.exception;

// ===== 400 Bad Request =====

/**
 * 잘못된 요청 데이터
 */
public class BadRequestException extends BusinessException {
    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BadRequestException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}