package com.jungwoo.project.memo.ai.brief;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlanBriefMapper {

    void insert(PlanBrief brief);

    PlanBrief findByConversationIdAndUserId(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    PlanBrief findByIdAndUserId(@Param("briefId") Long briefId, @Param("userId") Long userId);

    /** 다른 대화의 합의 중 최근 것. 새 대화가 확인된 어려움·유효한 합의를 이어 보게 한다. */
    List<PlanBrief> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** version 대조 갱신. 0행이면 그 사이 다른 턴이 바꾼 것이다. */
    int updateItems(@Param("briefId") Long briefId, @Param("userId") Long userId,
                    @Param("expectedVersion") int expectedVersion, @Param("items") String items,
                    @Param("status") String status);

    int updateLastProposal(@Param("briefId") Long briefId, @Param("userId") Long userId,
                           @Param("lastProposalId") Long lastProposalId);
}
