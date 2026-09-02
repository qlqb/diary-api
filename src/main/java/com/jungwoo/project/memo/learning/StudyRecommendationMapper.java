package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.StudyRecommendation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StudyRecommendationMapper {

    void insert(StudyRecommendation recommendation);

    StudyRecommendation findByIdAndUserId(@Param("recommendationId") Long recommendationId, @Param("userId") Long userId);

    List<StudyRecommendation> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    void updateStatus(@Param("recommendationId") Long recommendationId, @Param("status") String status);

    void attachProposal(@Param("recommendationId") Long recommendationId,
                         @Param("proposalId") Long proposalId,
                         @Param("status") String status);

    StudyRecommendation findByProposalIdAndUserId(@Param("proposalId") Long proposalId, @Param("userId") Long userId);
}
