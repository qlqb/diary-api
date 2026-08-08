package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CourseMaterialAnalysisMapper {

    void insert(CourseMaterialAnalysis analysis);

    CourseMaterialAnalysis findByIdAndUserId(@Param("analysisId") Long analysisId, @Param("userId") Long userId);

    List<CourseMaterialAnalysis> findByMaterialIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    void updateEditedJson(@Param("analysisId") Long analysisId, @Param("editedJson") String editedJson);

    void updateStatus(@Param("analysisId") Long analysisId,
                       @Param("status") String status,
                       @Param("appliedAt") LocalDateTime appliedAt);
}
