package com.jungwoo.project.memo.common.exception;

import com.jungwoo.project.memo.common.response.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 잘못된 요청 (파라미터, 상태 오류)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException e) {
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                e.getMessage()
        );
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e){
        return new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                e.getMessage());
    }

    /**
     * 인증 / 로그인 실패
     */
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleSecurity(SecurityException e) {
        return new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                e.getMessage()
        );
    }

    /**
     * 서버 내부 오류 (최후의 보루)
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleException(Exception e) {
        return new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal server error"
        );
    }
}
