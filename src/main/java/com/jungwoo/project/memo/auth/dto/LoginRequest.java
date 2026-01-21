package com.jungwoo.project.memo.auth.dto;
import lombok.Getter;

@Getter
public class LoginRequest {

    private String email;
    private String password;

    public LoginRequest() {
    }
}
