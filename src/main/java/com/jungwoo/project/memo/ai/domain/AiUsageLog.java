package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 호출 한 번의 사용량 기록. ai_usage_logs 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 전체 프롬프트나 API 키는 절대 담지 않는다 — 토큰 수/모델/요청 ID 같은 메타데이터만 남긴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageLog {

    private Long usageLogId;

    private Long userId;

    private Long conversationId;

    /** 이 사용량이 발생한 요청(ai_messages의 USER 행). */
    private Long requestMessageId;

    private String feature;

    private String model;

    private Integer inputTokens;

    /** Spring AI 표준 API로 얻을 수 없는 값. 채울 수 있게 되기 전까지 항상 null이며, 추측해 채우지 않는다. */
    private Integer cachedTokens;

    private Integer outputTokens;

    private UsageResultStatus resultStatus;

    private String errorCode;

    private String providerRequestId;

    private String requestId;

    private LocalDateTime createdAt;
}
