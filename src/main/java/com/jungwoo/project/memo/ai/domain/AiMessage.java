package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상담 메시지 하나. ai_messages 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * USER 메시지는 AI 호출 전에 먼저 저장한다. ASSISTANT 메시지는 스트림이 끝까지 성공했을 때만
 * 한 번 저장한다 — 중간에 실패하면 ASSISTANT 행은 생기지 않는다.
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

    /** 이 턴이 PROPOSAL이었으면 그 proposal_id. */
    private Long proposalId;

    /** 같은 사용자의 동일 idempotencyKey 재전송을 걸러내는 용도. USER 메시지만 값을 가진다. */
    private String idempotencyKey;

    private MessageStatus status;

    private LocalDateTime createdAt;
}
