package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialAnalysisControl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MaterialAnalysisControlMapper {

    MaterialAnalysisControl findByUserId(@Param("userId") Long userId);

    /** 행이 없으면 만들고 있으면 갱신한다. */
    int upsert(@Param("userId") Long userId, @Param("paused") boolean paused);
}
