package com.jungwoo.project.memo.user.dto;

import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class UserResponse {

    private Long userId;
    private String email;
    private String nickname;
    private String role;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserResponse(Long userId,
                        String email,
                        String nickname,
                        String role,
                        String status,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt) {
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
