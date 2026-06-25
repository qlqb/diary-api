package com.jungwoo.project.memo.user.domain;

import lombok.*;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 사용자 엔티티
 * Auth 도메인의 핵심 엔티티
 */
@Data                    // Lombok: getter, setter, toString, equals, hashCode 자동 생성
@NoArgsConstructor       // Lombok: 파라미터 없는 기본 생성자
@AllArgsConstructor      // Lombok: 모든 필드를 파라미터로 받는 생성자
@Builder                 // Lombok: 빌더 패턴 자동 생성
public class User {

    /** 사용자 고유 ID (Primary Key) */
    private Long userId;

    /** 이메일 주소 (로그인 ID, UNIQUE) */
    private String email;

    /** 암호화된 비밀번호 (BCrypt) */
    private String passwordHash;

    /** 사용자 닉네임 */
    private String nickname;

    /** 사용자 권한 (USER, ADMIN 등) */
    private String role;

    /** 계정 상태 (ACTIVE, INACTIVE, BANNED) */
    private String status;

    /** 마지막 로그인 시간 */
    private LocalDateTime lastLoginAt;

    /** 계정 생성 시간 */
    private LocalDateTime createdAt;

    /** 계정 정보 수정 시간 */
    private LocalDateTime updatedAt;
}