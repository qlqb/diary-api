package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.user.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * User 테이블 MyBatis 매퍼
 */
@Mapper
public interface UserMapper {

    /**
     * 사용자 등록
     */
    void insert(User user);

    /**
     * 이메일로 사용자 조회
     */
    User findByEmail(@Param("email") String email);

    /**
     * 사용자 ID로 조회
     */
    User findById(@Param("userId") Long userId);

    /**
     * 마지막 로그인 시간 업데이트
     */
    void updateLastLogin(
            @Param("userId") Long userId,
            @Param("lastLoginAt") LocalDateTime lastLoginAt
    );

    /**
     * 사용자 정보 수정
     */
    void update(User user);
}