package com.jungwoo.project.memo.user.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class User {

    private Long userId;
    private String email;
    private String passwordHash;
    private String nickname;
    private String role;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
