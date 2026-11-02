package com.jungwoo.project.memo.ai.draft;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ai_conversation_drafts 접근. 쓰기 메서드는 전부 대화 잠금(AiTurnLifecycleService가 잡은
 * active_request_message_id) 안에서만 호출한다 — 잠금 밖에서 부르는 경로를 만들지 않는다.
 * 유일한 예외는 {@link #cancelOpenByConversation}이며, 대화 ARCHIVED와 같은 트랜잭션이다.
 */
@Mapper
public interface AiConversationDraftMapper {

    void insert(AiConversationDraft draft);

    /** 첫 draft의 draft_group_id를 자기 id로 채운다(그룹 id는 별도 시퀀스가 아니다). */
    int updateGroupId(@Param("draftId") Long draftId, @Param("userId") Long userId,
                      @Param("draftGroupId") Long draftGroupId);

    /** 매 턴 프롬프트에 실을 OPEN draft 전부. 최근 갱신순. */
    List<AiConversationDraft> findOpenByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                                @Param("userId") Long userId);

    /** 상태 무관 전체(테스트·재생 확인용). */
    List<AiConversationDraft> findByConversationIdAndUserId(@Param("conversationId") Long conversationId,
                                                            @Param("userId") Long userId);

    /** 타입·라벨·상태·필드·누락·ask_count·승격 결과를 한 번에 갱신한다. */
    int update(AiConversationDraft draft);

    /** 대화 삭제(ARCHIVED) 시 OPEN draft 전부 CANCELLED. */
    int cancelOpenByConversation(@Param("conversationId") Long conversationId, @Param("userId") Long userId);
}
