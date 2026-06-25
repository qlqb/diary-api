package com.jungwoo.project.memo.common.exception;

/**
* 비즈니스 로직 예외의 최상위 클래스
* 모든 커스텀 예외는 이 클래스를 상속
*/
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}