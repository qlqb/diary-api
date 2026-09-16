package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ZipImportMapper {

    void insert(ZipImport zipImport);

    ZipImport findByIdAndUserId(@Param("importId") Long importId, @Param("userId") Long userId);

    /** 사용자의 최근 가져오기. 화면을 다시 열었을 때 진행 중/최근 결과를 되찾는 통로다. */
    List<ZipImport> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    int updateStatus(@Param("importId") Long importId, @Param("status") ZipImportStatus status,
                     @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    int updateListing(@Param("importId") Long importId, @Param("status") ZipImportStatus status,
                      @Param("entryCount") int entryCount, @Param("selectableCount") int selectableCount);

    /** 원본 zip을 지운 뒤 경로를 비운다. 자료는 각자 파일을 갖고 있어 영향받지 않는다. */
    int clearStoragePath(@Param("importId") Long importId);

    /** 정리 대상: 기한이 지났고 아직 원본을 들고 있는 것. */
    List<ZipImport> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 진행 중인 가져오기 중 남은 항목이 없는 것 — 마무리 판정 대상. */
    List<ZipImport> findImportingWithNoPendingEntries(@Param("limit") int limit);
}
