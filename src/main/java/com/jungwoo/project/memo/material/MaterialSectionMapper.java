package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialSection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialSectionMapper {

    /** INSERT IGNORE — 같은 (자료, 해시, 분석판, dedupe_key)면 0행. 겹친 청크의 중복은 여기서 막힌다. */
    int insertIgnore(MaterialSection section);

    MaterialSection findByIdAndUserId(@Param("sectionId") Long sectionId, @Param("userId") Long userId);

    /** 현재 파일 해시의 ACTIVE 구간. 위치 순. */
    List<MaterialSection> findActiveByMaterialIdAndHash(@Param("materialId") Long materialId,
                                                        @Param("fileHash") String fileHash);

    /** 자료의 ACTIVE 구간(해시 무관 — 현재 자료 행의 해시로 다시 거르는 것은 서비스 몫). */
    List<MaterialSection> findActiveByMaterialIds(@Param("materialIds") List<Long> materialIds,
                                                  @Param("userId") Long userId);

    List<MaterialSection> findByIdsAndUserId(@Param("sectionIds") List<Long> sectionIds,
                                             @Param("userId") Long userId);

    /** 파일이 바뀌어 옛 해시의 구간이 더는 현재가 아닐 때. */
    int supersedeOtherHashes(@Param("materialId") Long materialId, @Param("keepHash") String keepHash);

    /** 자료 삭제: 발췌 본문을 지운다(삭제가 삭제여야 한다). 행과 제목은 남긴다. */
    int clearTextByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
