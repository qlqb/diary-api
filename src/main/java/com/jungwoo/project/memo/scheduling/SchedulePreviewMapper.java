package com.jungwoo.project.memo.scheduling;

import com.jungwoo.project.memo.scheduling.domain.AiProposalSchedulePreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SchedulePreviewMapper {

    AiProposalSchedulePreview findByProposalIdAndUserId(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    void insert(AiProposalSchedulePreview preview);

    /** proposal_id당 하나만 있으므로 존재하면 update, 없으면 insert(서비스에서 먼저 조회해 분기). */
    int update(AiProposalSchedulePreview preview);
}
