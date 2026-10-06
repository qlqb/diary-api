package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialTextUnitMapper {

    void insert(MaterialTextUnit unit);

    /** 이 파일 해시의 단위들. unit_index 순. 없으면 빈 목록 — 아직 단위를 만들지 않은 자료다. */
    List<MaterialTextUnit> findByMaterialIdAndHash(@Param("materialId") Long materialId,
                                                   @Param("fileHash") String fileHash);

    int countByMaterialIdAndHash(@Param("materialId") Long materialId, @Param("fileHash") String fileHash);

    /** 단위 전체를 지운다. 같은 해시를 다시 만들 때(백필 재시도)만 쓴다. */
    int deleteByMaterialIdAndHash(@Param("materialId") Long materialId, @Param("fileHash") String fileHash);

    /**
     * 상담 근거 검색. 지정한 (자료, 파일 해시) 쌍의 단위 중 검색어가 하나라도 들어 있는 것을 걸린 검색어 수 순으로 돌려준다.
     * 본문은 싣지 않는다 — 점수가 높은 단위만 {@link #findByIdsAndUserId}로 다시 읽는다. 해시를 함께 묶어 옛 파일의 단위가
     * 섞이지 않게 한다.
     */
    List<TextUnitHit> searchByTerms(@Param("userId") Long userId,
                                    @Param("materials") List<com.jungwoo.project.memo.material.domain.CourseMaterial> materials,
                                    @Param("terms") List<String> terms,
                                    @Param("perMaterial") int perMaterial,
                                    @Param("limit") int limit);

    /** 사용자의 자료별(해시별) 단위 개수와 번호 범위. 상담 근거의 "읽을 수 있는가"를 한 번에 본다. */
    List<TextUnitSummary> summarizeByUser(@Param("userId") Long userId);

    List<MaterialTextUnit> findByIdsAndUserId(@Param("unitIds") List<Long> unitIds, @Param("userId") Long userId);

    /** 한 자료(현재 해시)의 단위 번호 범위. 추가 읽기에서 쓴다. */
    List<MaterialTextUnit> findRange(@Param("userId") Long userId, @Param("materialId") Long materialId,
                                     @Param("fileHash") String fileHash, @Param("unitFrom") int unitFrom,
                                     @Param("unitTo") int unitTo);

    /** 원문 삭제와 함께 텍스트도 지운다 — 삭제가 삭제여야 한다. */
    int deleteByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
