package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserContextMapper {

    void insert(UserContext context);

    UserContext findByIdAndUserId(@Param("contextId") Long contextId, @Param("userId") Long userId);

    /** apply 트랜잭션에서 행 잠금을 건다. */
    UserContext findByIdAndUserIdForUpdate(@Param("contextId") Long contextId, @Param("userId") Long userId);

    /**
     * ACTIVE/STALE만 조회한다(SUPERSEDED/ARCHIVED는 상담 프롬프트·조회 API 어디에도 노출하지
     * 않는다). limit이 null이면 전체를 반환한다(GET /api/contexts). limit이 있으면 최근
     * updated_at 순으로 그만큼만 반환한다(ContextSnapshotService 토큰 예산용).
     */
    List<UserContext> findActiveAndStaleByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    /**
     * fromStatuses에 포함될 때만 toStatus로 바꾼다(가드된 전이). 이미 다른 상태로 바뀌었으면
     * 0을 반환한다 — 호출부가 conflict로 처리한다.
     */
    int updateStatusIfIn(@Param("contextId") Long contextId, @Param("userId") Long userId,
                          @Param("fromStatuses") List<String> fromStatuses,
                          @Param("toStatus") UserContextStatus toStatus);

    /** CONFIRM 전용: STALE일 때만 ACTIVE로 바꾸고 confirmed_at을 채운다. */
    int confirmIfStale(@Param("contextId") Long contextId, @Param("userId") Long userId,
                        @Param("confirmedAt") LocalDateTime confirmedAt);
}
