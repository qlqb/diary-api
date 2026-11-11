package com.jungwoo.project.memo.plan.trace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlanGenerationTraceMapper {

    void insert(PlanGenerationTrace trace);

    List<PlanGenerationTrace> findByGenerationIdAndUserId(@Param("generationId") String generationId,
                                                          @Param("userId") Long userId);

    /** 자료 삭제: 그 자료가 실린 입력의 전문만 지운다. id·집계는 남는다. */
    int purgeTextByMaterial(@Param("userId") Long userId, @Param("materialToken") String materialToken);

    int deleteOlderThan(@Param("before") LocalDateTime before);
}
