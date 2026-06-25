package com.jungwoo.project.memo.auth.dto;

import com.jungwoo.project.memo.user.domain.User;
import lombok.Builder;
import lombok.Getter;

/**
 * 사용자 기본 정보 DTO
 *
 * 민감 정보(비밀번호 등)를 제외한 공개 가능한 정보만 포함
 */
@Getter
@Builder
public class UserInfo {

    /** 사용자 ID */
    private Long userId;

    /** 이메일 */
    private String email;

    /** 닉네임 */
    private String nickname;

    /** 역할 */
    private String role;

    /**
     * User 엔티티로부터 UserInfo 생성
     *
     * @param user User 엔티티
     * @return UserInfo
     */
    public static UserInfo from(User user) {
        return UserInfo.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole())
                .build();
    }
}