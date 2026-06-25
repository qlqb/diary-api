package com.jungwoo.project.memo.common.exception;

/**
* 잘못된 인증 정보 (이메일/비밀번호)
*/
public class InvalidCredentialsException extends UnauthorizedException {
    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}