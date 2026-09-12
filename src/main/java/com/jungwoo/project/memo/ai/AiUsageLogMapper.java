package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiUsageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AiUsageLogMapper {

    void insert(AiUsageLog usageLog);

    /** sinceInclusive 이후(오늘/이번 달 시작) 이 사용자의 AI 호출 횟수. 일/월 한도 체크용. */
    int countByUserIdSince(@Param("userId") Long userId, @Param("sinceInclusive") LocalDateTime sinceInclusive);
}
