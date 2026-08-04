package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai_messages 쓰기 전용. AiConversationService는 스트림 구독(네트워크 I/O)을 포함하므로
 * 그 메서드에 @Transactional을 걸지 않는다 — 실제 DB 쓰기만 이 별도 빈에 모아
 * (자기호출 문제 없이) 트랜잭션 경계를 보장한다. AiProposalPersistenceService와 같은 이유.
 */
@Service
@RequiredArgsConstructor
public class AiMessagePersistenceService {

    private final AiMessageMapper aiMessageMapper;
    private final AiConversationMapper aiConversationMapper;

    /** USER 메시지는 AI 호출 전에 먼저 저장한다 — AI가 실패해도 사용자 원문은 남는다. */
    @Transactional
    public AiMessage saveUserMessage(Long conversationId, Long userId, String content, String idempotencyKey) {
        AiMessage message = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .idempotencyKey(idempotencyKey)
                .status(MessageStatus.COMPLETED)
                .build();
        aiMessageMapper.insert(message);
        return message;
    }

    /** ASSISTANT 메시지는 스트림이 끝까지 성공했을 때 한 번만 저장한다. */
    @Transactional
    public AiMessage saveAssistantMessage(
            Long conversationId, Long userId, String content, AiResponseType responseType, Long proposalId
    ) {
        AiMessage message = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .responseType(responseType)
                .proposalId(proposalId)
                .status(MessageStatus.COMPLETED)
                .build();
        aiMessageMapper.insert(message);
        aiConversationMapper.touchUpdatedAt(conversationId, userId);
        return message;
    }
}
