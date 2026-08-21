package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialLinkMapper {

    void insert(MaterialLink link);

    MaterialLink findByMaterialIdAndCourseIdAndUserId(@Param("materialId") Long materialId,
                                                        @Param("courseId") Long courseId,
                                                        @Param("userId") Long userId);

    /** 그 프로젝트에 연결된 링크 전부. materialId -> materialType 매핑을 만들 때 쓴다. */
    List<MaterialLink> findByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /**
     * 이 자료가 걸려 있는 연결 중 ACTIVE 프로젝트로의 연결만. 자료 상세의 "연결된 프로젝트"에 쓴다.
     * 보관된 프로젝트로의 연결은 행이 남아있어도(보관 해제 시 복원) 여기서는 보이지 않는다.
     */
    List<MaterialLink> findByMaterialIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /**
     * 사용자의 링크 중 ACTIVE 프로젝트로의 연결만. 전역 자료 목록에서 자료별 연결을 한 번에
     * 채울 때 쓴다 — 이래야 "ARCHIVED 링크만 남은 자료"가 연결 안 된 자료처럼 보인다.
     */
    List<MaterialLink> findByUserId(@Param("userId") Long userId);

    /**
     * 이 프로젝트에서 이 자료가 맡는 역할을 바꾼다. 자료 본체(course_materials)는 건드리지
     * 않는다 — 같은 파일이 다른 프로젝트에서는 다른 역할 그대로 남아야 한다.
     */
    void updateMaterialType(@Param("materialId") Long materialId,
                             @Param("courseId") Long courseId,
                             @Param("userId") Long userId,
                             @Param("materialType") MaterialType materialType);

    /** 연결 해제. 자료 원본·분석 이력·확정된 topic/note는 건드리지 않는다. */
    void delete(@Param("materialId") Long materialId,
                 @Param("courseId") Long courseId,
                 @Param("userId") Long userId);

    /** 자료 삭제 시 그 자료의 모든 연결을 끊는다. */
    void deleteAllByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
