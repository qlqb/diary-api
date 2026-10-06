package com.jungwoo.project.memo.common.exception;

// ===== 409 Conflict =====

/**
 * 중복된 리소스가 존재할 때 발생
 */
public class ConflictException extends BusinessException {
    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 무엇이 걸렸는지를 화면이 보여줘야 하는 충돌. details는 그대로 응답 본문의 details가 된다.
     */
    public ConflictException(ErrorCode errorCode, Object details) {
        super(errorCode, errorCode.getMessage(), details);
    }
}
