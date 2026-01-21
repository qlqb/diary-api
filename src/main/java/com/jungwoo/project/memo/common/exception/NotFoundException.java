package com.jungwoo.project.memo.common.exception;

public class NotFoundException extends  RuntimeException{
    public NotFoundException(String message){
        super(message);
    }
}
