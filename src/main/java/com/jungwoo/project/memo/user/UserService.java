package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.user.domain.User;
import com.jungwoo.project.memo.auth.dto.LoginRequest;
import com.jungwoo.project.memo.user.dto.UserCreateRequest;
import com.jungwoo.project.memo.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 회원 가입
     */
    @Transactional
    public void createUser(UserCreateRequest request) {
        User user = new User();
        user.setEmail(request.getEmail());

        // TODO: Spring Security 도입 시 BCrypt 적용
        user.setPasswordHash(request.getPassword());

        user.setNickname(request.getNickname());
        user.setRole("USER");
        user.setStatus("ACTIVE");

        userMapper.insertUser(user);
    }

    /**
     * 회원 단건 조회
     */
    public UserResponse getUser(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new NotFoundException("User not found. userId=" + userId);
        }

        return new UserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    public UserResponse login(LoginRequest request) {
        User user = userMapper.findByEmail(request.getEmail());
        if (user == null) {
            throw new NotFoundException("User not found. userEmail=" + request.getEmail());
        }

        // TODO: BCrypt 도입 시 passwordEncoder.matches()
        if (!user.getPasswordHash().equals(request.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        userMapper.updateLastLogin(user.getUserId());

        return new UserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

}
