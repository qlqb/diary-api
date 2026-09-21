package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyEdits;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프로젝트 정리 작업·정리안·근거 자료·사용자 편집.
 *
 * <p>동시성의 핵심 셋:
 * <ul>
 *   <li>{@code insertJob} — 프로젝트당 열린 작업 하나를 DB가 지킨다(부분 유일 인덱스에 걸리면
 *       DuplicateKeyException). 중복 클릭·두 탭이 같은 작업을 두 번 만들지 못한다.</li>
 *   <li>{@code claimJob} — 자료 분석과 같은 임대 방식. 잡을 때마다 토큰이 오르고 결과 저장은
 *       토큰을 대조한다.</li>
 *   <li>{@code finishJobWithResult} — 토큰과 세대를 <b>같은 문장에서</b> 대조한다. 그 사이
 *       사용자가 버렸으면(작업 CANCELLED) 0행이라 결과가 버려진다.</li>
 * </ul>
 */
@Mapper
public interface ProjectTidyMapper {

    // ===== 작업 =====

    void insertJob(ProjectTidyJob job);

    ProjectTidyJob findJobById(@Param("jobId") Long jobId);

    ProjectTidyJob findJobByIdAndUserId(@Param("jobId") Long jobId, @Param("userId") Long userId);

    /** 이 프로젝트에서 지금 도는 작업. 없으면 null. */
    ProjectTidyJob findOpenJobByCourse(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 이 프로젝트의 마지막 작업(끝난 것 포함). 실패를 화면에 보여주는 데 쓴다. */
    ProjectTidyJob findLatestJobByCourse(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 이 프로젝트에서 지금까지 나온 가장 큰 세대. 새 요청은 이보다 1 크다. */
    Long findMaxGeneration(@Param("courseId") Long courseId);

    List<ProjectTidyJob> findClaimableJobs(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claimJob(@Param("jobId") Long jobId, @Param("owner") String owner, @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    int renewJobLease(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
                      @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 결과를 매달며 작업을 끝낸다. 임대 토큰이 같고 아직 RUNNING일 때만 — 그 사이 취소됐으면 0행이다.
     */
    int finishJob(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken, @Param("status") String status,
                  @Param("resultProposalId") Long resultProposalId, @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage, @Param("finishedAt") LocalDateTime finishedAt);

    int rescheduleJob(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken,
                      @Param("nextRunAt") LocalDateTime nextRunAt, @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage);

    /** 사용자가 버렸거나 프로젝트가 보관됐다. 도는 작업을 무효화한다. */
    int cancelOpenJobs(@Param("courseId") Long courseId, @Param("userId") Long userId,
                       @Param("reason") String reason);

    /** 결과를 저장하기 직전, 작업 행을 잠그고 아직 유효한지 본다. 무효면 null. */
    Long lockJobIfLeased(@Param("jobId") Long jobId, @Param("leaseToken") Long leaseToken);

    // ===== 정리안 =====

    void insertProposal(ProjectTidyProposal proposal);

    ProjectTidyProposal findProposalById(@Param("proposalId") Long proposalId, @Param("userId") Long userId);

    ProjectTidyProposal findProposalByIdForUpdate(@Param("proposalId") Long proposalId,
                                                  @Param("userId") Long userId);

    /** 이 프로젝트에서 지금 검토 중인 정리안. 없으면 null. */
    ProjectTidyProposal findOpenProposalByCourse(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<ProjectTidyProposal> findHistoryByCourse(@Param("courseId") Long courseId, @Param("userId") Long userId,
                                                  @Param("limit") int limit);

    int updateProposalStatus(@Param("proposalId") Long proposalId, @Param("userId") Long userId,
                             @Param("status") String status, @Param("appliedResultJson") String appliedResultJson,
                             @Param("supersededByProposalId") Long supersededByProposalId,
                             @Param("resolvedAt") LocalDateTime resolvedAt);

    /**
     * 적용·폐기 전이. 아직 PROPOSED이고 판 번호가 기대와 같을 때만 1행이다 — 두 탭이 동시에
     * 적용을 눌러도 한 번만 반영된다.
     */
    int resolveProposalIfOpen(@Param("proposalId") Long proposalId, @Param("userId") Long userId,
                              @Param("expectedRevision") Long expectedRevision, @Param("status") String status,
                              @Param("appliedResultJson") String appliedResultJson,
                              @Param("resolvedAt") LocalDateTime resolvedAt);

    // ===== 근거 자료 =====

    void insertProposalMaterial(ProjectTidyProposalMaterial material);

    List<ProjectTidyProposalMaterial> findProposalMaterials(@Param("proposalId") Long proposalId,
                                                            @Param("userId") Long userId);

    // ===== 사용자 편집 =====

    ProjectTidyEdits findEdits(@Param("proposalId") Long proposalId, @Param("userId") Long userId);

    ProjectTidyEdits findEditsForUpdate(@Param("proposalId") Long proposalId, @Param("userId") Long userId);

    /** 처음 저장. 이미 있으면 0행이고 호출자가 update로 넘어간다. */
    int insertEditsIgnore(ProjectTidyEdits edits);

    /** 판 번호가 기대와 같을 때만 1행. 다른 탭이 먼저 저장했으면 0행이다. */
    int updateEdits(@Param("proposalId") Long proposalId, @Param("userId") Long userId,
                    @Param("expectedRevision") Long expectedRevision, @Param("editsJson") String editsJson);
}
