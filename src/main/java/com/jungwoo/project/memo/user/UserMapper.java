package com.jungwoo.project.memo.user;

import com.jungwoo.project.memo.user.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    /**
     * 회원 등록
     */
    int insertUser(User user);

    /**
     * 회원 단건 조회
     */
    User findById(@Param("userId") Long userId);

    /**
     * 이메일로 회원 조회 (로그인용)
     */
    User findByEmail(@Param("email") String email);

    /**
     * 마지막 로그인 시각 업데이트
     */
    int updateLastLogin(@Param("userId") Long userId);
}
