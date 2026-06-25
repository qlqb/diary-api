package com.jungwoo.project.memo.common.exception;

/**
* 일기를 찾을 수 없음
*/
public class DiaryNotFoundException extends NotFoundException {
    public DiaryNotFoundException() {
        super(ErrorCode.DIARY_NOT_FOUND);
    }
}