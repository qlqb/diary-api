package com.jungwoo.project.memo.auth.service;

import com.jungwoo.project.memo.auth.dto.AuthResponse;
import com.jungwoo.project.memo.auth.dto.LoginRequest;
import com.jungwoo.project.memo.auth.dto.SignupRequest;
import com.jungwoo.project.memo.auth.dto.UserInfo;
import com.jungwoo.project.memo.common.exception.EmailAlreadyExistsException;
import com.jungwoo.project.memo.common.exception.InvalidCredentialsException;
import com.jungwoo.project.memo.common.security.JwtUtil;
import com.jungwoo.project.memo.user.UserMapper;
import com.jungwoo.project.memo.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 인증 서비스
 *
 * 회원가입, 로그인 등 인증 관련 비즈니스 로직 처리
 *
 * 주요 책임:
 * - 회원가입 (이메일 중복 검사, 비밀번호 암호화)
 * - 로그인 (인증 정보 검증, JWT 토큰 발급)
 * - 마지막 로그인 시간 기록
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 회원가입
     *
     * 처리 흐름:
     * 1. 이메일 중복 검사
     * 2. 비밀번호 암호화 (BCrypt)
     * 3. User 엔티티 생성
     * 4. DB 저장
     * 5. JWT 토큰 생성
     * 6. 응답 DTO 반환
     *
     * @param request 회원가입 요청 (email, password, nickname)
     * @return 인증 응답 (token, user)
     * @throws EmailAlreadyExistsException 이메일이 이미 존재하는 경우
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        log.info("회원가입 시도: email={}", request.getEmail());

        // 1. 이메일 중복 검사
        if (userMapper.findByEmail(request.getEmail()) != null) {
            log.warn("이메일 중복: {}", request.getEmail());
            throw new EmailAlreadyExistsException();
        }

        // 2. User 엔티티 생성
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .role("USER")
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // 3. DB 저장
        userMapper.insert(user);
        log.info("회원가입 성공: userId={}", user.getUserId());

        // 4. JWT 토큰 생성
        String token = jwtUtil.generateToken(
                user.getUserId(),
                user.getEmail(),
                user.getRole()
        );

        // 5. 응답 반환
        return AuthResponse.builder()
                .token(token)
                .user(UserInfo.from(user))
                .build();
    }

    /**
     * 로그인
     *
     * 처리 흐름:
     * 1. 이메일로 사용자 조회
     * 2. 비밀번호 검증
     * 3. 마지막 로그인 시간 업데이트
     * 4. JWT 토큰 생성
     * 5. 응답 DTO 반환
     *
     * @param request 로그인 요청 (email, password)
     * @return 인증 응답 (token, user)
     * @throws InvalidCredentialsException 인증 정보가 틀린 경우
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.info("로그인 시도: email={}", request.getEmail());

        // 1. 이메일로 사용자 조회
        User user = userMapper.findByEmail(request.getEmail());
        if (user == null) {
            log.warn("로그인 실패 email={}", request.getEmail());
            throw new InvalidCredentialsException();
        }

        // 2. 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("로그인 실패 email={}", request.getEmail());
            throw new InvalidCredentialsException();
        }

        // 3. 마지막 로그인 시간 업데이트
        userMapper.updateLastLogin(user.getUserId(), LocalDateTime.now());
        log.info("로그인 성공: userId={}", user.getUserId());

        // 4. JWT 토큰 생성
        String token = jwtUtil.generateToken(
                user.getUserId(),
                user.getEmail(),
                user.getRole()
        );

        // 5. 응답 반환
        return AuthResponse.builder()
                .token(token)
                .user(UserInfo.from(user))
                .build();
    }
}