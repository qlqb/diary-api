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
    private int dailyLimit = 50;

    @Value("${ai.usage.monthly-limit:1000}")
    private int monthlyLimit = 1000;

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

    /** Today 상담 전용 — feature는 항상 AI_CONSULTATION으로 고정된다. 기존 호출부를 그대로 유지한다. */
    @Transactional
    public void record(Long userId, Long conversationId, Long requestMessageId, String model,
                        Integer inputTokens, Integer cachedTokens, Integer outputTokens,
                        UsageResultStatus resultStatus, String errorCode) {
        record(userId, conversationId, requestMessageId, model, inputTokens, cachedTokens, outputTokens,
                resultStatus, errorCode, FEATURE, null, null, null);
    }

    /**
     * Material/Learning/Planning Agent처럼 feature를 구분해서 남겨야 하는 경우용 오버로드.
     * workflowId/agentRunId는 Orchestrator가 여러 Agent를 연쇄 호출할 때 같은 워크플로임을
     * 묶어 추적하기 위한 값이다(단일 호출이면 null로 둬도 된다).
     */
    @Transactional
    public void record(Long userId, Long conversationId, Long requestMessageId, String model,
                        Integer inputTokens, Integer cachedTokens, Integer outputTokens,
                        UsageResultStatus resultStatus, String errorCode, String feature,
                        String workflowId, String agentRunId, Integer latencyMs) {
        try {
            aiUsageLogMapper.insert(AiUsageLog.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .workflowId(workflowId)
                    .agentRunId(agentRunId)
                    .requestMessageId(requestMessageId)
                    .feature(feature != null ? feature : FEATURE)
                    .model(model)
                    .inputTokens(inputTokens)
                    .cachedTokens(cachedTokens)
                    .outputTokens(outputTokens)
                    .latencyMs(latencyMs)
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
