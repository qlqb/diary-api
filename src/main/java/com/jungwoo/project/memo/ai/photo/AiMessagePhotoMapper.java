package com.jungwoo.project.memo.ai.photo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** ai_message_photos — 어느 사용자 메시지에 어떤 상담 사진을 붙였나. */
@Mapper
public interface AiMessagePhotoMapper {

    record Row(Long messageId, Long materialId) {
    }

    int insert(@Param("messageId") Long messageId, @Param("materialId") Long materialId, @Param("userId") Long userId,
               @Param("conversationId") Long conversationId);

    /** 대화의 모든 첨부(메시지 순). 기록 복원용. */
    List<Row> findByConversation(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /**
     * 활성 사진: 이 대화에서 uptoMessageId 이하 가장 최근에 사진을 붙인 사용자 메시지의 사진 중 아직 살아 있는 자료.
     * 새 사진을 붙이면 바뀐다(턴 수 제한 없음).
     */
    List<Long> findActivePhotoIds(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                                  @Param("uptoMessageId") Long uptoMessageId);

    /** 이 메시지 이하에서 같은 대화에 붙은 사진(가까운 메시지 먼저). 계획의 "그 어려움을 말할 때 보던 사진". */
    List<Long> findPhotoIdsUpTo(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                                @Param("uptoMessageId") Long uptoMessageId);

    /** 메시지가 속한 대화(기억의 근거 메시지에서 대화를 찾는다). */
    Long findConversationOfMessage(@Param("messageId") Long messageId, @Param("userId") Long userId);
}
