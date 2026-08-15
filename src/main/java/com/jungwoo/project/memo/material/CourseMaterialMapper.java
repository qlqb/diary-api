package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMaterialMapper {

    void insert(CourseMaterial material);

    /** ACTIVE 자료만. 목록·상세·업로드 후속·분석·AI 컨텍스트 등 기본 경로 전부 이걸 쓴다. */
    CourseMaterial findByIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /** DELETED도 반환한다. provenance 표시("원본 삭제됨 · 파일명") 전용. */
    CourseMaterial findByIdAndUserIdIncludingDeleted(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /** ACTIVE 자료만. material_links로 그 프로젝트에 연결된 자료를 가져온다. */
    List<CourseMaterial> findByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 사용자의 전체 ACTIVE 자료. 전역 자료함 목록용. */
    List<CourseMaterial> findAllByUserId(@Param("userId") Long userId);

    /** soft delete. 파일 원문은 이 호출 이후 서비스가 별도로 디스크에서 지운다. */
    void markDeleted(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
