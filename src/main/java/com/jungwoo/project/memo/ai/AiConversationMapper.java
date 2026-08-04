package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiConversationMapper {

    void insert(AiConversation conversation);

    AiConversation findByIdAndUserId(Long conversationId, Long userId);

    /** 대화방 잠금 확인/획득 트랜잭션에서 행 잠금을 건다. MariaDB: SELECT ... FOR UPDATE. */
    AiConversation findByIdAndUserIdForUpdate(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    void touchUpdatedAt(Long conversationId, Long userId);

    /** SELECT ... FOR UPDATE로 이미 행을 잠근 뒤에만 호출한다 — 그 잠금이 동시 요청을 직렬화한다. */
    void acquireActiveRequest(@Param("conversationId") Long conversationId,
                               @Param("userId") Long userId,
                               @Param("requestMessageId") Long requestMessageId);

    /**
     * 진행 표시 해제. active_request_message_id가 지금도 requestMessageId와 같을 때만 지운다 —
     * 더 늦게 끝난 이전 요청이 새 요청의 진행 표시를 잘못 지우는 것을 막는다.
     */
    int releaseActiveRequest(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId,
                              @Param("requestMessageId") Long requestMessageId);
}
