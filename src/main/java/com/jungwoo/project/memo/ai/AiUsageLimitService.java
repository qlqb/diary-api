package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiUsageLog;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 사용자별 일/월 AI 호출 한도. Redis 등 외부 시스템 없이 기존 ai_usage_logs 집계만으로 계산한다.
 * 전체 프롬프트나 API 키는 절대 로그에 남기지 않는다 — 메타데이터만 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageLimitService {

    private static final String FEATURE = "AI_CONSULTATION";

    private final AiUsageLogMapper aiUsageLogMapper;

    @Value("${ai.usage.daily-limit:50}")
    private int dailyLimit;

    @Value("${ai.usage.monthly-limit:1000}")
    private int monthlyLimit;

    @Transactional(readOnly = true)
    public void checkLimit(Long userId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        int todayCount = aiUsageLogMapper.countByUserIdSince(userId, startOfDay);
        if (todayCount >= dailyLimit) {
            log.warn("AI 일일 호출 한도 초과: userId={}, count={}, limit={}", userId, todayCount, dailyLimit);
            throw new TooManyRequestsException(ErrorCode.AI_USAGE_LIMIT_EXCEEDED);
        }

        LocalDateTime startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
        int monthCount = aiUsageLogMapper.countByUserIdSince(userId, startOfMonth);
        if (monthCount >= monthlyLimit) {
            log.warn("AI 월간 호출 한도 초과: userId={}, count={}, limit={}", userId, monthCount, monthlyLimit);
            throw new TooManyRequestsException(ErrorCode.AI_USAGE_LIMIT_EXCEEDED);
        }
    }

    @Transactional
    public void record(Long userId, Long conversationId, Long requestMessageId, String model,
                        Integer inputTokens, Integer cachedTokens, Integer outputTokens,
                        UsageResultStatus resultStatus, String errorCode) {
        try {
            aiUsageLogMapper.insert(AiUsageLog.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .requestMessageId(requestMessageId)
                    .feature(FEATURE)
                    .model(model)
                    .inputTokens(inputTokens)
                    .cachedTokens(cachedTokens)
                    .outputTokens(outputTokens)
                    .resultStatus(resultStatus != null ? resultStatus : UsageResultStatus.SUCCESS)
                    .errorCode(errorCode)
                    // Spring AI 표준 API로는 provider request id를 안정적으로 얻을 수 없다 — 추측해 채우지 않는다.
                    .providerRequestId(null)
                    .build());
        } catch (Exception e) {
            // 사용량 로그 저장 실패로 상담 자체를 실패시키지 않는다.
            log.warn("AI 사용량 로그 저장 실패: userId={}", userId, e);
        }
    }
}
