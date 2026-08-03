package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiProposalItemMapper {

    void insert(AiProposalItem item);

    List<AiProposalItem> findByProposalIdAndUserId(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    int updateAfterApply(
            @Param("proposalItemId") Long proposalItemId,
            @Param("userId") Long userId,
            @Param("status") AiProposalItemStatus status,
            @Param("editedPayload") String editedPayload,
            @Param("createdItemType") String createdItemType,
            @Param("createdItemId") Long createdItemId,
            @Param("respondedAt") LocalDateTime respondedAt
    );
}
