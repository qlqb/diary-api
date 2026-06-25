package com.jungwoo.project.memo.auth.controller;

import com.jungwoo.project.memo.auth.dto.AuthResponse;
import com.jungwoo.project.memo.auth.dto.LoginRequest;
import com.jungwoo.project.memo.auth.dto.SignupRequest;
import com.jungwoo.project.memo.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 컨트롤러
 *
 * 회원가입, 로그인 API 제공
 *
 * 개선사항:
 * - try-catch 제거: GlobalExceptionHandler가 자동으로 예외 처리
 * - 간결한 코드: 성공 케이스만 작성
 * - 명확한 HTTP 상태 코드 반환
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 회원가입 API
     *
     * POST /api/auth/signup
     *
     * @param request 회원가입 요청 (email, password, nickname)
     * @return 201 Created + 인증 응답
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        log.info("POST /api/auth/signup - 회원가입 요청: email={}", request.getEmail());

        AuthResponse response = authService.signup(request);

        // 201 Created: 새로운 리소스가 생성됨
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 로그인 API
     *
     * POST /api/auth/login
     *
     * @param request 로그인 요청 (email, password)
     * @return 200 OK + 인증 응답
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("POST /api/auth/login - 로그인 요청: email={}", request.getEmail());

        AuthResponse response = authService.login(request);

        // 200 OK
        return ResponseEntity.ok(response);
    }
}