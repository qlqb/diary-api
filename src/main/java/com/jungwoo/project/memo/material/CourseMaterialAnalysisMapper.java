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

    /**
     * 이 프로젝트에서 적용된 분석들. 계획 생성이 강의 일정(개강일·시험)을 읽는 데 쓴다.
     *
     * keyDates는 별도 테이블이 없고 analysis_json 안에만 있다 — course_topics/course_notes와
     * 달리 apply가 따로 꺼내 저장하지 않는다. 그래서 여기서 원문을 읽어 파싱한다.
     */
    List<CourseMaterialAnalysis> findAppliedByCourseIdAndUserId(@Param("courseId") Long courseId,
                                                                 @Param("userId") Long userId);

    /**
     * 같은 맥락(user_id + course_id + material_id)의 열린 DRAFT 하나.
     *
     * <p>analyze()가 AI를 부르기 전에 이걸로 먼저 확인한다. 다만 이 조회만으로는 두 요청이
     * 동시에 "없음"을 읽는 경쟁을 막지 못한다 — 최종 방어선은 DB의
     * uq_course_material_analyses_single_draft다.
     *
     * <p>LIMIT 1이지만 정렬을 붙이는 이유는 마이그레이션 전 레거시 데이터에 중복 DRAFT가
     * 남아 있을 수 있어서다. created_at이 같을 때 흔들리지 않도록 analysis_id까지 본다.
     */
    CourseMaterialAnalysis findLatestDraftByContext(@Param("userId") Long userId,
                                                     @Param("courseId") Long courseId,
                                                     @Param("materialId") Long materialId);

    void updateEditedJson(@Param("analysisId") Long analysisId, @Param("editedJson") String editedJson);

    void updateStatus(@Param("analysisId") Long analysisId,
                       @Param("status") String status,
                       @Param("appliedAt") LocalDateTime appliedAt);
}
