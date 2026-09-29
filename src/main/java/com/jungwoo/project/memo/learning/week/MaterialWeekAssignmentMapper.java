package com.jungwoo.project.memo.learning.week;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialWeekAssignmentMapper {

    /** 이 프로젝트의 확정 자리 전부. 지워진 자료·끊긴 연결의 행은 빼고 준다. */
    List<MaterialWeekAssignment> findActiveByCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    /**
     * 한 자료의 자리를 바꾸는 동안 같은 자료의 다른 변경을 기다리게 한다. 연결 행을 잠근다 — 없으면
     * 그 자료는 이 프로젝트에 연결되어 있지 않다(null).
     */
    Long lockLink(@Param("userId") Long userId, @Param("courseId") Long courseId,
                  @Param("materialId") Long materialId);

    List<MaterialWeekAssignment> findByMaterial(@Param("userId") Long userId, @Param("courseId") Long courseId,
                                                @Param("materialId") Long materialId);

    int deleteByMaterial(@Param("userId") Long userId, @Param("courseId") Long courseId,
                         @Param("materialId") Long materialId);

    int insert(MaterialWeekAssignment assignment);
}
