package com.jungwoo.project.memo.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security의 인증 Principal 객체
 * JWT에서 추출한 사용자 정보를 담아 SecurityContext에 저장
 *
 * UserDetails 인터페이스 구현으로 Spring Security와 통합
 */
@Getter
public class UserPrincipal implements UserDetails {

    /** 사용자 고유 ID - 가장 중요한 식별자 */
    private final Long userId;

    /** 이메일 (로그인 ID로 사용) */
    private final String email;

    /** 사용자 역할 (USER, ADMIN 등) */
    private final String role;

    /**
     * UserPrincipal 생성자
     *
     * @param userId 사용자 ID
     * @param email 이메일
     * @param role 사용자 역할
     */
    public UserPrincipal(Long userId, String email, String role) {
        this.userId = userId;
        this.email = email;
        this.role = role;
    }

    /**
     * Spring Security가 권한을 확인할 때 사용
     *
     * @return 사용자 권한 목록 (ROLE_ 접두사 포함)
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 단일 역할을 권한 목록으로 변환
        // "USER" -> "ROLE_USER"
        return Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + role)
        );
    }

    /**
     * Spring Security가 사용자명으로 인식하는 값
     * 우리는 이메일을 사용자명으로 사용
     *
     * @return 이메일
     */
    @Override
    public String getUsername() {
        return email;
    }

    /**
     * 비밀번호 반환 (JWT 방식에서는 사용 안 함)
     * JWT 토큰 인증에서는 비밀번호 확인이 필요 없음
     *
     * @return null (사용 안 함)
     */
    @Override
    public String getPassword() {
        return null;  // JWT 방식에서는 비밀번호 불필요
    }

    /**
     * 계정 만료 여부
     *
     * @return true (만료되지 않음)
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 계정 잠김 여부
     *
     * @return true (잠기지 않음)
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 인증 정보 만료 여부
     *
     * @return true (만료되지 않음)
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 계정 활성화 여부
     *
     * @return true (활성화됨)
     */
    @Override
    public boolean isEnabled() {
        return true;
    }
}