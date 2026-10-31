package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ZipImportEntryMapper {

    void insert(ZipImportEntry entry);

    List<ZipImportEntry> findByImportId(@Param("importId") Long importId, @Param("userId") Long userId);

    ZipImportEntry findByIdAndUserId(@Param("entryId") Long entryId, @Param("userId") Long userId);

    /**
     * 고른 항목을 대기열에 넣는다. PENDING·FAILED만 QUEUED가 된다 — 이미 자료가 된 항목(DONE)은
     * 몇 번을 눌러도 다시 만들어지지 않는다.
     */
    int queueSelected(@Param("importId") Long importId, @Param("userId") Long userId,
                      @Param("entryIds") List<Long> entryIds);

    /** 작업자의 선점. QUEUED일 때만 1행이 바뀐다 — 두 작업자가 같은 항목을 집지 못한다. */
    int claim(@Param("entryId") Long entryId);

    /** 선점 대상 목록(오래된 것부터). */
    List<ZipImportEntry> findQueued(@Param("limit") int limit);

    /** 서버가 내려가 IMPORTING으로 남은 항목을 다시 대기열로. materialId가 있으면 건드리지 않는다. */
    int requeueStale(@Param("before") LocalDateTime before, @Param("maxAttempt") int maxAttempt);

    int markFailed(@Param("entryId") Long entryId, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /** 확정으로 만들어진 자료와 항목을 잇는다. 자료 INSERT와 같은 트랜잭션에서만 부른다. */
    int markDone(@Param("entryId") Long entryId, @Param("materialId") Long materialId);

    int countByStatus(@Param("importId") Long importId, @Param("status") ZipEntryStatus status);

    int countRemaining(@Param("importId") Long importId);
}
