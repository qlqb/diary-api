package com.jungwoo.project.memo.course.textbook;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialTextbookExtractMapper {

    /** INSERT IGNORE — 같은 (자료, 해시, 판)이 이미 있으면 0행. 재분석·재시도·동시 조회가 행을 늘리지 않는다. */
    int insertIgnore(MaterialTextbookExtract extract);

    MaterialTextbookExtract find(@Param("materialId") Long materialId, @Param("fileHash") String fileHash,
                                 @Param("extractorVersion") int extractorVersion, @Param("userId") Long userId);

    List<MaterialTextbookExtract> findCurrent(@Param("materialIds") List<Long> materialIds,
                                              @Param("extractorVersion") int extractorVersion,
                                              @Param("userId") Long userId);
}
