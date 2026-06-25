package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.auth.dto.UserInfo;
import com.jungwoo.project.memo.common.exception.UserNotFoundException;
import com.jungwoo.project.memo.user.domain.User;
import com.jungwoo.project.memo.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 서비스
 *
 * 사용자 정보 조회 및 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    /**
     * 사용자 기본 정보 조회
     *
     * @param userId 사용자 ID
     * @return 사용자 기본 정보
     * @throws UserNotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public UserInfo getUserInfo(Long userId) {
        User user = userMapper.findById(userId);

        if (user == null) {
            log.warn("사용자를 찾을 수 없음: userId={}", userId);
            throw new UserNotFoundException();
        }

        return UserInfo.from(user);
    }

    /**
     * 사용자 상세 정보 조회
     *
     * @param userId 사용자 ID
     * @return 사용자 상세 정보
     * @throws UserNotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public UserResponse getUserDetail(Long userId) {
        User user = userMapper.findById(userId);

        if (user == null) {
            log.warn("사용자를 찾을 수 없음: userId={}", userId);
            throw new UserNotFoundException();
        }

        return UserResponse.from(user);
    }
}