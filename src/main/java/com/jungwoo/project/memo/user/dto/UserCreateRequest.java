package com.jungwoo.project.memo.user.dto;

import lombok.Getter;

@Getter
public class UserCreateRequest {

    private String email;
    private String password;   // 평문 비밀번호 (서비스에서 해시)
    private String nickname;

    public UserCreateRequest() {
    }

    public UserCreateRequest(String email, String password, String nickname) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
    }
}
