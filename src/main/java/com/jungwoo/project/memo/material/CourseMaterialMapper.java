package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMaterialMapper {

    void insert(CourseMaterial material);

    CourseMaterial findByIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    List<CourseMaterial> findByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    void updateExtractionResult(@Param("materialId") Long materialId,
                                 @Param("extractionStatus") String extractionStatus,
                                 @Param("extractedText") String extractedText,
                                 @Param("extractionError") String extractionError);
}
