package com.jungwoo.project.memo.common.exception;

/**
* 비즈니스 로직 예외의 최상위 클래스
* 모든 커스텀 예외는 이 클래스를 상속
*/
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 화면이 읽을 구조화된 부가 정보. 대부분의 예외는 null이다.
     *
     * <p>있는 이유: 어떤 오류는 "무엇이 걸렸는지"를 목록으로 돌려줘야 화면이 그것을 보여주고
     * 사용자가 정리할 수 있다(반복 일정 수정이 기존 예외를 무효화하는 경우가 그렇다).
     * 그 목록을 message 문자열에 나열해 프론트가 파싱하게 두지 않는다 — 서버 문구와 화면이
     * 결합되어, 문구를 다듬는 순간 화면이 깨진다.
     *
     * <p>errors[]로 대신하지 않는 이유는 그쪽이 BindingResult 전용(field/value/reason 문자열)
     * 이라 구조를 담을 자리가 아니기 때문이다.
     */
    private final Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}