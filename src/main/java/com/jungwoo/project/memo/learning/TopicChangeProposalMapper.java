package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TopicChangeProposalMapper {

    /** 같은 (자료, 프로젝트)에 열린 변경안이 있으면 DuplicateKeyException. */
    void insert(TopicChangeProposal proposal);

    TopicChangeProposal findByIdAndUserId(@Param("proposalId") Long proposalId, @Param("userId") Long userId);

    TopicChangeProposal findByIdAndUserIdForUpdate(@Param("proposalId") Long proposalId, @Param("userId") Long userId);

    List<TopicChangeProposal> findOpenByCourseId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<TopicChangeProposal> findByCourseId(@Param("courseId") Long courseId, @Param("userId") Long userId,
                                             @Param("limit") int limit);

    TopicChangeProposal findOpenByMaterialAndCourse(@Param("materialId") Long materialId,
                                                    @Param("courseId") Long courseId,
                                                    @Param("userId") Long userId);

    int updateStatus(@Param("proposalId") Long proposalId, @Param("userId") Long userId,
                     @Param("status") String status, @Param("appliedOpsJson") String appliedOpsJson,
                     @Param("resolvedAt") LocalDateTime resolvedAt);

    /** 자료 삭제·연결 해제·프로젝트 보관: 열린 변경안을 STALE로. */
    int staleOpen(@Param("materialId") Long materialId, @Param("courseId") Long courseId,
                  @Param("userId") Long userId, @Param("resolvedAt") LocalDateTime resolvedAt);
}
