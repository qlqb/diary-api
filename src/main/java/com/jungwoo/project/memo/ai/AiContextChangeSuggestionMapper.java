package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiContextChangeSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiContextChangeSuggestionMapper {

    void insert(AiContextChangeSuggestion suggestion);

    AiContextChangeSuggestion findByIdAndUserId(@Param("suggestionId") Long suggestionId, @Param("userId") Long userId);

    /** apply/dismiss 트랜잭션에서 행 잠금을 건다. */
    AiContextChangeSuggestion findByIdAndUserIdForUpdate(@Param("suggestionId") Long suggestionId, @Param("userId") Long userId);

    /** 대화 재진입 시 복구용 — 아직 PROPOSED인 것만. */
    List<AiContextChangeSuggestion> findPendingByConversationIdAndUserId(
            @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** idempotency 재생용 — 이 ASSISTANT 메시지가 만든 후보 전체(상태 무관). */
    List<AiContextChangeSuggestion> findBySourceMessageIdAndUserId(
            @Param("sourceMessageId") Long sourceMessageId, @Param("userId") Long userId);

    /** PROPOSED일 때만 APPLIED로 바꾸고 resultingContextId를 채운다(가드된 전이). */
    int markApplied(@Param("suggestionId") Long suggestionId, @Param("userId") Long userId,
                     @Param("resolvedAt") LocalDateTime resolvedAt,
                     @Param("resultingContextId") Long resultingContextId);

    /** PROPOSED일 때만 DISMISSED로 바꾼다(가드된 전이). */
    int markDismissed(@Param("suggestionId") Long suggestionId, @Param("userId") Long userId,
                       @Param("resolvedAt") LocalDateTime resolvedAt);
}
