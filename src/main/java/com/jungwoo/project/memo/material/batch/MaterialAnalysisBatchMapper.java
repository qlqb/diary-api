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

    /**
     * 열린 묶음 한 쪽. batch_id 내림차순, cursor보다 작은 것만.
     *
     * <p>개수 상한을 올리는 것으로는 풀리지 않는다 — 몇 개로 올리든 그다음 하나가 잘리고,
     * 잘린 묶음을 화면은 끝난 것으로 추측한다. 끝까지 넘겨 볼 수 있어야 한다.
     */
    List<MaterialAnalysisBatch> findOpenPage(@Param("userId") Long userId, @Param("courseId") Long courseId,
                                             @Param("before") Long before, @Param("limit") int limit);

    int countOpen(@Param("userId") Long userId, @Param("courseId") Long courseId);

    /**
     * 상태 전이. 지금 상태가 from일 때만 1행이다.
     *
     * <p>조회가 상태를 옮기는 자리라 두 요청이 같은 순간에 옮길 수 있다. 조건 없이 쓰면
     * "다시 열림"이 뒤늦은 "끝남"에 덮인다.
     */
    int transitionStatus(@Param("batchId") Long batchId, @Param("userId") Long userId,
                         @Param("from") String from, @Param("to") String to,
                         @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 이 자료가 든 <끝난> 묶음을 다시 연다. 실패한 자료를 다시 돌릴 때 부른다.
     *
     * <p>열린 목록은 저장된 상태로 거른다. 여기서 열어 두지 않으면 재시도 중인 묶음이 목록에
     * 나타나지 않아, 화면에 돌아온 사용자는 진행을 볼 수 없다.
     */
    int reopenFinishedContaining(@Param("userId") Long userId, @Param("materialId") Long materialId);

    /**
     * 올라오지 않은 자리를 거둔다. 파일을 고른 브라우저가 떠났으면 그 자리는 다시 올 수 없다 —
     * 브라우저의 File 객체는 새로고침과 함께 사라진다.
     */
    int abandonOrphanedItems(@Param("before") LocalDateTime before, @Param("message") String message);
}
