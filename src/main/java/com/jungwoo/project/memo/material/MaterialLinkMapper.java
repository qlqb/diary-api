package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialLink;
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

    /** 이 자료가 걸려 있는 연결 전부. 자료 상세의 "연결된 프로젝트"에 쓴다. */
    List<MaterialLink> findByMaterialIdAndUserId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /** 사용자의 모든 링크. 전역 자료 목록에서 자료별 연결을 한 번에 채울 때 쓴다. */
    List<MaterialLink> findByUserId(@Param("userId") Long userId);

    /** 연결 해제. 자료 원본·분석 이력·확정된 topic/note는 건드리지 않는다. */
    void delete(@Param("materialId") Long materialId,
                 @Param("courseId") Long courseId,
                 @Param("userId") Long userId);

    /** 자료 삭제 시 그 자료의 모든 연결을 끊는다. */
    void deleteAllByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
