package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.domain.PlanItemDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlanItemDetailMapper {

    /** INSERT IGNORE — 같은 (항목, 근거판)이 이미 있으면 0행(동시 요청은 먼저 저장한 쪽이 이긴다). */
    int insertIgnore(PlanItemDetail detail);

    PlanItemDetail findByItemAndVersion(@Param("proposalItemId") Long proposalItemId,
                                        @Param("evidenceVersion") String evidenceVersion,
                                        @Param("userId") Long userId);

    PlanItemDetail findByIdAndUserId(@Param("detailId") Long detailId, @Param("userId") Long userId);

    List<PlanItemDetail> findByItem(@Param("proposalItemId") Long proposalItemId, @Param("userId") Long userId);

    /** 다른 근거판을 STALE로. 새 판을 저장한 뒤 부른다. */
    int staleOthers(@Param("proposalItemId") Long proposalItemId, @Param("keepVersion") String keepVersion,
                    @Param("userId") Long userId);

    int updateUserText(@Param("detailId") Long detailId, @Param("userId") Long userId,
                       @Param("userText") String userText);
}
