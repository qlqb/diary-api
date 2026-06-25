package com.jungwoo.project.memo.common.exception;

/**
* 이메일 중복
*/
public class EmailAlreadyExistsException extends ConflictException {
    public EmailAlreadyExistsException() {
        super(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
}