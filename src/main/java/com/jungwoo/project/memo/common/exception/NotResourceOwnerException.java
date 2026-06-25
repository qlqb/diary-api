package com.jungwoo.project.memo.common.exception;

/**
 * 리소스 소유자가 아닌 경우
 */
public class NotResourceOwnerException extends ForbiddenException {
    public NotResourceOwnerException() {
        super(ErrorCode.NOT_RESOURCE_OWNER);
    }
}