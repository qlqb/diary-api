package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMessageMapper {

    void insert(AiMessage message);

    /** 대화 전체 이력. GET .../messages 응답용. */
    List<AiMessage> findByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                   @Param("userId") Long userId);

    /**
     * 컨텍스트 스냅샷용 — 오래된 순으로 최근 limit개만(DB에서 LIMIT, 전체를 메모리로 읽지
     * 않는다). excludeMessageId가 있으면 그 메시지는 결과에서 제외한다(LIMIT 적용 전에
     * 걸러낸다) — 현재 처리 중인 사용자 요청 메시지가 이미 PROCESSING으로 저장돼 있어도
     * "최근 대화"에 자기 자신이 끼어들지 않게 하기 위함이다. null이면 아무것도 제외하지 않는다.
     */
    List<AiMessage> findRecentByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                          @Param("userId") Long userId,
                                                          @Param("limit") int limit,
                                                          @Param("excludeMessageId") Long excludeMessageId);

    /** 동일 사용자의 동일 idempotencyKey 재전송 감지용. */
    AiMessage findByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                             @Param("idempotencyKey") String idempotencyKey);

    /** 이 USER 요청에 대한 ASSISTANT 응답(있으면 하나뿐). idempotency 재생, 히스토리 표시용. */
    AiMessage findByReplyToMessageIdAndUserId(@Param("replyToMessageId") Long replyToMessageId,
                                               @Param("userId") Long userId);

    /**
     * from 상태일 때만 to로 바꾼다(가드된 전이). 이미 다른 경로로 종료 처리됐으면 0을 반환하고
     * 아무것도 바뀌지 않는다 — 완료·실패·취소 처리기가 두 번 실행돼도 중복 반영을 막는다.
     */
    int updateStatusIfCurrent(@Param("messageId") Long messageId,
                               @Param("userId") Long userId,
                               @Param("fromStatus") MessageStatus fromStatus,
                               @Param("toStatus") MessageStatus toStatus);
}
