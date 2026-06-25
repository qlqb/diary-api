package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.auth.dto.UserInfo;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 컨트롤러
 *
 * 사용자 정보 조회 API 제공
 *
 * @AuthenticationPrincipal 사용 예시:
 * - Spring Security가 JWT 필터에서 설정한 인증 정보를 자동 주입
 * - 헤더에서 토큰을 직접 파싱할 필요 없음
 * - 타입 세이프하게 사용자 정보 접근
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 현재 로그인한 사용자 정보 조회
     *
     * GET /api/users/me
     *
     * 사용 목적:
     * - 프론트엔드에서 페이지 새로고침 후 사용자 정보 복구
     * - 토큰만 있으면 사용자 정보 재조회 가능
     * - 토큰 유효성 검증 겸용
     *
     * @param principal JWT 필터가 설정한 인증 사용자 정보
     * @return 200 OK + 사용자 정보
     */
    @GetMapping("/me")
    public ResponseEntity<UserInfo> getCurrentUser(
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("GET /api/users/me - 현재 사용자 조회: userId={}", principal.getUserId());

        // UserPrincipal에서 바로 사용자 정보 조회
        UserInfo userInfo = userService.getUserInfo(principal.getUserId());

        return ResponseEntity.ok(userInfo);
    }

    /**
     * 사용자 상세 정보 조회
     *
     * GET /api/users/me/detail
     *
     * @param principal 인증된 사용자
     * @return 200 OK + 상세 정보
     */
    @GetMapping("/me/detail")
    public ResponseEntity<UserResponse> getCurrentUserDetail(
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("GET /api/users/me/detail - 사용자 상세 조회: userId={}",
                principal.getUserId());

        UserResponse response = userService.getUserDetail(principal.getUserId());

        return ResponseEntity.ok(response);
    }
}