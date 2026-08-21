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

    /**
     * 이 자료의 분석 전부. 프로젝트 맥락을 섞어서 돌려준다 — 전역 자료 상세("이 자료가 지금까지
     * 어디서 어떻게 해석됐는가")에서만 쓴다. 프로젝트 화면에서는 쓰지 않는다.
     */
    List<CourseMaterialAnalysis> findByMaterialIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /**
     * 이 자료를 이 프로젝트 맥락에서 해석한 분석만. Analysis = Material x Project라는 경계는
     * analyze()/apply()뿐 아니라 조회에도 서 있어야 한다 — 같은 자료가 두 프로젝트에 걸려
     * 있을 때 다른 프로젝트의 해석이 함께 나오면 그건 경계가 아니다.
     */
    List<CourseMaterialAnalysis> findByMaterialIdAndCourseIdAndUserId(@Param("materialId") Long materialId,
                                                                        @Param("courseId") Long courseId,
                                                                        @Param("userId") Long userId);

    void updateEditedJson(@Param("analysisId") Long analysisId, @Param("editedJson") String editedJson);

    void updateStatus(@Param("analysisId") Long analysisId,
                       @Param("status") String status,
                       @Param("appliedAt") LocalDateTime appliedAt);
}
