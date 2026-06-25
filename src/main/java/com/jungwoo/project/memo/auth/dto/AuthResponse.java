package com.jungwoo.project.memo.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 로그인/회원가입 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    /** JWT 액세스 토큰 */
    private String token;

    /** 사용자 정보 */
    private UserInfo user;
}