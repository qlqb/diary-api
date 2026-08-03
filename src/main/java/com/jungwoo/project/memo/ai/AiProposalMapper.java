package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AiProposalMapper {

    void insert(AiProposal proposal);

    AiProposal findByIdAndUserId(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    /**
     * apply 트랜잭션에서 행 잠금을 건다. MySQL: SELECT ... FOR UPDATE.
     */
    AiProposal findByIdAndUserIdForUpdate(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    int updateStatusAndRespondedAt(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId,
            @Param("status") AiProposalStatus status,
            @Param("respondedAt") LocalDateTime respondedAt
    );
}
