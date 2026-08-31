package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiScheduleSuggestionMapper {

    void insert(AiScheduleSuggestion suggestion);

    AiScheduleSuggestion findByIdAndUserId(@Param("suggestionId") Long suggestionId,
                                           @Param("userId") Long userId);

    /**
     * 적용·거절 트랜잭션에서 행을 잠근다. 상태 전이를 읽고 쓰는 사이에 다른 요청이 같은
     * 후보를 결론지으면 약속이 두 번 만들어질 수 있다.
     */
    AiScheduleSuggestion findByIdAndUserIdForUpdate(@Param("suggestionId") Long suggestionId,
                                                    @Param("userId") Long userId);

    /** 대화 재진입 시 복원할 미처리 후보. */
    List<AiScheduleSuggestion> findPendingByConversationIdAndUserId(
            @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** idempotency 재생용 — 그 ASSISTANT 메시지가 만든 후보 전체(상태 무관). */
    List<AiScheduleSuggestion> findBySourceMessageIdAndUserId(
            @Param("sourceMessageId") Long sourceMessageId, @Param("userId") Long userId);

    /**
     * PROPOSED일 때만 결론으로 바꾼다. 갱신 행 수가 0이면 그 사이 누가 먼저 결론지은 것이다 —
     * 잠금과 함께 이 조건이 재적용·재거절을 막는 두 번째 방어선이다.
     */
    int resolveIfProposed(@Param("suggestionId") Long suggestionId,
                          @Param("userId") Long userId,
                          @Param("status") String status,
                          @Param("resolvedAt") LocalDateTime resolvedAt);
}
