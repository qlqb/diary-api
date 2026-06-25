package com.jungwoo.project.memo.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 토큰을 검증하고 Spring Security 인증 객체를 생성하는 필터
 *
 * OncePerRequestFilter: 요청당 한 번만 실행되는 필터
 *
 * 동작 흐름:
 * 1. HTTP 요청 헤더에서 JWT 토큰 추출
 * 2. JWT 토큰 유효성 검증
 * 3. 토큰에서 사용자 정보 추출
 * 4. UserPrincipal 객체 생성
 * 5. Spring Security 인증 객체(Authentication) 생성
 * 6. SecurityContext에 인증 정보 저장
 *
 * 이후 컨트롤러에서는 @AuthenticationPrincipal로 사용자 정보 접근 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * 모든 HTTP 요청에 대해 실행되는 필터 메서드
     *
     * @param request HTTP 요청
     * @param response HTTP 응답
     * @param filterChain 다음 필터 체인
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            // 1. Authorization 헤더에서 JWT 토큰 추출
            String jwt = extractJwtFromRequest(request);

            // 2. 토큰이 존재하고 유효한지 검증
            if (StringUtils.hasText(jwt) && jwtUtil.validateToken(jwt)) {

                // 3. JWT에서 사용자 정보 추출
                Long userId = jwtUtil.getUserIdFromToken(jwt);
                String email = jwtUtil.getEmailFromToken(jwt);
                String role = jwtUtil.getRoleFromToken(jwt);  // role 추가 추출

                // 4. UserPrincipal 생성 (Spring Security의 Principal 객체)
                UserPrincipal userPrincipal = new UserPrincipal(userId, email, role);

                // 5. Spring Security 인증 객체 생성
                // UsernamePasswordAuthenticationToken: Spring Security의 표준 인증 토큰
                // 생성자 파라미터: (principal, credentials, authorities)
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userPrincipal,           // principal: 인증된 사용자 정보
                                null,                    // credentials: 자격증명 (JWT에서는 불필요)
                                userPrincipal.getAuthorities()  // authorities: 권한 목록
                        );

                // 6. 인증 객체에 요청 세부 정보 추가 (IP 주소, 세션 ID 등)
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                // 7. SecurityContext에 인증 정보 저장
                // 이후 컨트롤러에서 SecurityContextHolder.getContext().getAuthentication()으로 접근 가능
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("JWT 인증 성공: userId={}, email={}", userId, email);
            }
        } catch (Exception ex) {
            // JWT 파싱/검증 실패 시 로그만 남기고 계속 진행
            // 인증이 필요한 엔드포인트는 SecurityConfig에서 차단됨
            log.error("JWT 인증 처리 중 오류 발생: {}", ex.getMessage());
        }

        // 8. 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }

    /**
     * HTTP 요청 헤더에서 JWT 토큰 추출
     *
     * Authorization 헤더 형식: "Bearer {token}"
     *
     * @param request HTTP 요청
     * @return JWT 토큰 문자열 (없으면 null)
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        // Authorization 헤더 값 가져오기
        String bearerToken = request.getHeader("Authorization");

        // "Bearer "로 시작하는지 확인하고 토큰 부분만 추출
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);  // "Bearer " 제거
        }

        return null;
    }
}