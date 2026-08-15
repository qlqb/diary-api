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
}
