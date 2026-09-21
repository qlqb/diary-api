package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisBatch;
import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisBatchItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 업로드·분석 묶음과 그 고정된 구성원. */
@Mapper
public interface MaterialAnalysisBatchMapper {

    void insert(MaterialAnalysisBatch batch);

    void insertItem(MaterialAnalysisBatchItem item);

    MaterialAnalysisBatch findByIdAndUserId(@Param("batchId") Long batchId, @Param("userId") Long userId);

    List<MaterialAnalysisBatchItem> findItems(@Param("batchId") Long batchId, @Param("userId") Long userId);

    MaterialAnalysisBatchItem findItemForUpdate(@Param("itemId") Long itemId, @Param("userId") Long userId);

    /**
     * 열려 있는 묶음. 화면을 떠났다 돌아왔을 때 "아직 도는 것이 있다"를 복원하는 데 쓴다.
     * courseId가 null이면 사용자의 모든 묶음.
     */
    List<MaterialAnalysisBatch> findOpenByUser(@Param("userId") Long userId, @Param("courseId") Long courseId,
                                               @Param("limit") int limit);

    /** 이 자료가 속한 묶음 자리. 자료 삭제·분석 종료에서 묶음을 되짚을 때. */
    List<MaterialAnalysisBatchItem> findItemsByMaterialIds(@Param("materialIds") List<Long> materialIds,
                                                           @Param("userId") Long userId);

    int updateItemUpload(@Param("itemId") Long itemId, @Param("userId") Long userId,
                         @Param("uploadState") String uploadState, @Param("materialId") Long materialId,
                         @Param("message") String message,
                         @Param("estMinSeconds") Integer estMinSeconds,
                         @Param("estMaxSeconds") Integer estMaxSeconds);

    int updateStatus(@Param("batchId") Long batchId, @Param("userId") Long userId, @Param("status") String status,
                     @Param("startedAt") LocalDateTime startedAt, @Param("finishedAt") LocalDateTime finishedAt);

    int updateEstimate(@Param("batchId") Long batchId, @Param("userId") Long userId,
                       @Param("estMinSeconds") Integer estMinSeconds, @Param("estMaxSeconds") Integer estMaxSeconds,
                       @Param("estBasis") String estBasis);

    /** 아무것도 올리지 않은 채 남은 오래된 묶음 정리. 폴러가 부른다. */
    int abandonStale(@Param("before") LocalDateTime before);
}
