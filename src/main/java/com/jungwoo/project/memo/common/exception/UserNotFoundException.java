package com.jungwoo.project.memo.common.exception;

/**
 * 사용자를 찾을 수 없음
 */
public class UserNotFoundException extends NotFoundException {
    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND);
    }
}
