package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.auth.dto.LoginRequest;
import com.jungwoo.project.memo.user.dto.UserCreateRequest;
import com.jungwoo.project.memo.user.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 회원가입
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void signup(@RequestBody UserCreateRequest request) {
        userService.createUser(request);
    }

    /**
     * 회원 단건 조회 (테스트 / 디버그용)
     */
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable Long userId) {
        return userService.getUser(userId);
    }

    /**
     * 로그인 (임시 버전)
     * - JWT / 세션 없음
     * - email + password 검증 후 UserResponse 반환
     */
    @PostMapping("/login")
    public UserResponse login(@RequestBody LoginRequest request) {
        return userService.login(request);
    }
}
