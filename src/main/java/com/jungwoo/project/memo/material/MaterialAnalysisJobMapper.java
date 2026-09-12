package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MaterialAnalysisJobMapper {

    /** INSERT IGNORE — 같은 범위(자료·프로젝트·종류·해시·분석판)의 작업이 이미 있으면 0행. */
    int insertIgnore(MaterialAnalysisJob job);

    MaterialAnalysisJob findById(@Param("jobId") Long jobId);

    MaterialAnalysisJob findByScope(@Param("materialId") Long materialId, @Param("courseId") Long courseId,
                                    @Param("jobKind") String jobKind, @Param("fileHash") String fileHash,
                                    @Param("analysisVersion") Integer analysisVersion);

    /**
     * 선점 후보. 실행 가능한(QUEUED이고 시각이 됐거나 RUNNING인데 임대가 만료된) 행을
     * priority, next_run_at, job_id 순으로. 일시중지한 사용자의 작업은 빠진다.
     * minPriority로 "backfill만" 같은 부분 조회를 한다.
     */
    List<MaterialAnalysisJob> findClaimable(@Param("now") LocalDateTime now,
                                            @Param("minPriority") Integer minPriority,
                                            @Param("limit") int limit);

    /**
     * 원자적 선점. 영향 행이 1이어야 실행한다. leaseToken은 +1, attempt는 +1.
     * 조건에 status를 다시 넣어 두 worker가 같은 행을 둘 다 잡는 일을 막는다.
     */
    int claim(@Param("jobId") Long jobId, @Param("owner") String owner,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 임대 연장. 0행이면 임대를 잃은 것이다 — 즉시 중단한다. */
    int renewLease(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 청크 하나가 끝났다. 토큰 대조. */
    int updateProgress(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
                       @Param("totalChunks") Integer totalChunks, @Param("completedChunks") Integer completedChunks,
                       @Param("checkpointJson") String checkpointJson);

    /** 종료 상태(DONE/PARTIAL/FAILED/UNAVAILABLE/CANCELLED)로. 토큰 대조. */
    int finish(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
               @Param("status") String status, @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage, @Param("resultRefId") Long resultRefId,
               @Param("finishedAt") LocalDateTime finishedAt);

    /** 재시도 예약. QUEUED로 돌리고 next_run_at을 미룬다. 토큰 대조. */
    int reschedule(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
                   @Param("nextRunAt") LocalDateTime nextRunAt, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /** 사용자가 다시 시도. 종료 상태의 작업을 QUEUED로 되돌리고 시도 횟수를 초기화한다. */
    int requeue(@Param("jobId") Long jobId, @Param("userId") Long userId, @Param("priority") Integer priority,
                @Param("now") LocalDateTime now);

    /** 사용자 재개 — PAUSED 작업 전부를 QUEUED로. */
    int resumeAllPaused(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 사용자 일시중지 — QUEUED 작업 전부를 PAUSED로(RUNNING은 끝나게 둔다). */
    int pauseAllQueued(@Param("userId") Long userId);

    /** 자료 삭제 시 아직 안 끝난 작업을 CANCELLED로. */
    int cancelOpenByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /** 연결 해제 시 그 (자료, 프로젝트)의 LINK 작업을 CANCELLED로. */
    int cancelOpenLinkJobs(@Param("materialId") Long materialId, @Param("courseId") Long courseId,
                           @Param("userId") Long userId);

    List<MaterialAnalysisJob> findByUserId(@Param("userId") Long userId);

    List<MaterialAnalysisJob> findByMaterialIds(@Param("materialIds") List<Long> materialIds,
                                                @Param("userId") Long userId);

    /** 오늘 시작한(선점된) 작업 수 — 사용자별 하루 한도. */
    int countStartedSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** CONTENT 결과가 있는데 LINK 작업이 아직 없는 (자료, 프로젝트) 링크. LINK 등록 대상. */
    List<MaterialAnalysisJob> findContentDoneWithoutLink(@Param("analysisVersion") Integer analysisVersion,
                                                         @Param("limit") int limit);
}
