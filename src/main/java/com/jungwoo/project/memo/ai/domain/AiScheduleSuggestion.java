package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI가 대화에서 구조화한 일정 사실 후보. ai_schedule_suggestions와 1:1 대응한다.
 *
 * <p><b>일정 저장소가 아니다.</b> 오늘·일정 화면도 가용시간 계산도 이 테이블을 보지 않는다.
 * 사용자가 승인해야 실제 원본(one_off_commitments / routines)에 행이 생긴다.
 *
 * <p>proposedPayload는 승인 시 그대로 도메인 요청으로 읽히는 JSON이다. 필드 이름을
 * CommitmentCreateRequest / RoutineSaveRequest와 맞춰 둔 것이 요점이다 — AI 전용 이름을
 * 따로 만들면 승인 경로에 변환 계층이 생기고, 그 계층이 기존 검증을 우회할 자리가 된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiScheduleSuggestion {

    private Long suggestionId;

    private Long userId;

    private Long conversationId;

    /** 이 후보를 만든 ASSISTANT 메시지. 한 메시지에서 여러 후보가 나올 수 있다. */
    private Long sourceMessageId;

    private ScheduleSuggestionKind kind;

    private String proposedPayload;

    private ScheduleSuggestionStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;
}
