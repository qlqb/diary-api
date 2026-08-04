package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상담 메시지 하나. ai_messages 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * USER 메시지는 대화방의 진행 권한을 원자적으로 획득하면서 PROCESSING 상태로 먼저 저장한다.
 * AI 스트림이 끝까지 성공하면 ASSISTANT 메시지를 저장하고 USER 메시지를 COMPLETED로 바꾼다.
 * 오류·타임아웃·연결 종료면 ASSISTANT 행은 만들지 않고 USER 메시지만 FAILED로 바꾼다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessage {

    private Long messageId;

    private Long conversationId;

    private Long userId;

    private MessageRole role;

    private String content;

    /** ASSISTANT 메시지만 값을 가진다. */
    private AiResponseType responseType;

    /** ASSISTANT 메시지만 값을 가진다 — 이 응답이 어떤 USER 요청에 대한 것인지. */
    private Long replyToMessageId;

    /** 같은 사용자의 동일 idempotencyKey 재전송을 걸러내는 용도. USER 메시지만 값을 가진다. */
    private String idempotencyKey;

    private MessageStatus status;

    private LocalDateTime createdAt;
}
