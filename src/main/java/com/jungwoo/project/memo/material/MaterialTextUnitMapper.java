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

    /** 원문 삭제와 함께 텍스트도 지운다 — 삭제가 삭제여야 한다. */
    int deleteByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
