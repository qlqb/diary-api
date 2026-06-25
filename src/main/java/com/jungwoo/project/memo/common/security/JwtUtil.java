package com.jungwoo.project.memo.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 토큰 생성 및 검증 유틸리티
 *
 * JWT (JSON Web Token) 구조:
 * - Header: 알고리즘, 토큰 타입
 * - Payload: 사용자 정보 (Claims)
 * - Signature: 변조 방지 서명
 */
@Slf4j
@Component
public class JwtUtil {

    // application.properties에서 주입
    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * JWT 토큰 생성
     *
     * @param userId 사용자 ID
     * @param email 이메일
     * @param role 사용자 역할
     * @return 생성된 JWT 토큰
     */
    public String generateToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(userId.toString())      // 주체: 사용자 ID
                .claim("email", email)           // 커스텀 클레임: 이메일
                .claim("role", role)             // 커스텀 클레임: 역할
                .issuedAt(now)                   // 발급 시간
                .expiration(expiryDate)          // 만료 시간
                .signWith(key)                   // 서명
                .compact();
    }

    /**
     * JWT 토큰에서 사용자 ID 추출
     *
     * @param token JWT 토큰
     * @return 사용자 ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * JWT 토큰에서 이메일 추출
     *
     * @param token JWT 토큰
     * @return 이메일
     */
    public String getEmailFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("email", String.class);
    }

    /**
     * JWT 토큰에서 역할 추출
     *
     * @param token JWT 토큰
     * @return 역할 (USER, ADMIN 등)
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

    /**
     * JWT 토큰 유효성 검증
     *
     * @param token 검증할 토큰
     * @return 유효하면 true
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * JWT 토큰 파싱 (내부 메서드)
     *
     * @param token 파싱할 토큰
     * @return Claims 객체
     */
    private Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());

        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}