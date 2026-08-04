package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiConversationMapper {

    void insert(AiConversation conversation);

    AiConversation findByIdAndUserId(Long conversationId, Long userId);

    void touchUpdatedAt(Long conversationId, Long userId);
}
