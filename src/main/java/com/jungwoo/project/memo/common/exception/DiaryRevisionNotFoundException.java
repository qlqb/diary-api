package com.jungwoo.project.memo.common.exception;

/**
* 일기 수정 이력을 찾을 수 없음
*/
public class DiaryRevisionNotFoundException extends NotFoundException {
    public DiaryRevisionNotFoundException() {
        super(ErrorCode.DIARY_REVISION_NOT_FOUND);
    }
}