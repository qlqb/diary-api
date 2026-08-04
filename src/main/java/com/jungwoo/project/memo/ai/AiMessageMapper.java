package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMessageMapper {

    void insert(AiMessage message);

    /** 대화 전체 이력. GET .../messages 응답용. */
    List<AiMessage> findByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                   @Param("userId") Long userId);

    /** 컨텍스트 스냅샷용 — 오래된 순으로 최근 limit개만. */
    List<AiMessage> findRecentByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                          @Param("userId") Long userId,
                                                          @Param("limit") int limit);

    /** 동일 사용자의 동일 idempotencyKey 재전송 감지용. */
    AiMessage findByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                             @Param("idempotencyKey") String idempotencyKey);

    /** idempotency 재생 시, 해당 사용자 메시지 다음에 저장된 ASSISTANT 응답을 찾는다. */
    AiMessage findNextAssistantReply(@Param("conversationId") Long conversationId,
                                      @Param("afterMessageId") Long afterMessageId);
}
