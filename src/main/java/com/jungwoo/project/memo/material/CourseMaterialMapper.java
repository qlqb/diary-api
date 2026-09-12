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

    /**
     * DELETED도 반환한다. provenance 표시("원본 삭제됨 · 파일명") 전용.
     *
     * topic 트리는 자료 하나당 여러 항목이 달리므로 항상 여러 건을 한 번에 묻는다 —
     * 단건 조회를 반복하면 트리 크기만큼 쿼리가 늘어난다.
     */
    List<CourseMaterial> findByIdsAndUserIdIncludingDeleted(@Param("materialIds") List<Long> materialIds,
                                                              @Param("userId") Long userId);

    /** ACTIVE 자료만. material_links로 그 프로젝트에 연결된 자료를 가져온다. */
    List<CourseMaterial> findByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 사용자의 전체 ACTIVE 자료. 전역 자료함 목록용. */
    List<CourseMaterial> findAllByUserId(@Param("userId") Long userId);

    /**
     * ACTIVE 자료를 행 잠금과 함께 가져온다(SELECT ... FOR UPDATE). 연결 제안 apply 전용.
     *
     * 잠그는 것이 material_links가 아니라 course_materials인 것이 핵심이다 — 미연결 자료에는
     * 링크 행이 아예 없으므로 링크 테이블을 잠그면 아무것도 안 잠긴다. 빈 범위의 갭 락에
     * 기대면 격리 수준에 따라 동작이 달라져 추론할 수 없으므로, 이미 존재하는 자료 행을
     * 잠금 기준으로 삼는다.
     *
     * 호출부는 materialId 오름차순으로 한 번에 부른다 — 그룹별로 나눠 잠그면 두 요청이 서로
     * 다른 순서로 진입해 데드락이 난다.
     */
    List<CourseMaterial> lockActiveByIdsAndUserId(@Param("materialIds") List<Long> materialIds,
                                                  @Param("userId") Long userId);

    /** soft delete. 파일 원문은 이 호출 이후 서비스가 별도로 디스크에서 지운다. */
    void markDeleted(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
