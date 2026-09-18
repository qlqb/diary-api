package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface AiProposalMapper {

    void insert(AiProposal proposal);

    AiProposal findByIdAndUserId(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    /**
     * apply 트랜잭션에서 행 잠금을 건다. MySQL: SELECT ... FOR UPDATE.
     */
    AiProposal findByIdAndUserIdForUpdate(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    int updateStatusAndRespondedAt(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId,
            @Param("status") AiProposalStatus status,
            @Param("respondedAt") LocalDateTime respondedAt
    );

    /**
     * 계획 초안 생성 직후 기간·강도·목표 시간과 판단을 붙인다. 계획 경로 전용.
     *
     * planStrategyJson은 판단층을 거치지 않은 초안이면 null이다 — 그때는 컬럼도 NULL로
     * 남고 확정이 복사할 것도 없다.
     */
    int updatePlanMetadata(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId,
            @Param("planStartDate") LocalDate planStartDate,
            @Param("planEndDate") LocalDate planEndDate,
            @Param("planIntensity") PlanIntensity planIntensity,
            @Param("planTargetMinutes") Integer planTargetMinutes,
            @Param("planStrategyJson") String planStrategyJson,
            @Param("planProvenanceJson") String planProvenanceJson
    );

    /** 초안을 만든 요청(PlanRequestContext JSON). 같은 조건으로 다시 만들기가 읽는다. */
    /** 검토 상태 저장. PROPOSED인 초안만, 늦은 저장(옛 version)은 0행. */
    int updateReviewState(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId,
            @Param("reviewStateJson") String reviewStateJson,
            @Param("expectedVersion") Integer expectedVersion
    );

    int updatePlanRequest(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId,
            @Param("planRequestJson") String planRequestJson
    );

    /** 이 ASSISTANT 메시지가 만든 제안(있으면 하나뿐). 대화 이력 표시·idempotency 재생용. */
    /**
     * 같은 요청 키로 이미 만든 열린 초안. 화면이 중복 클릭·재시도로 같은 키를 다시 보내면 생성 대신 이것을 돌려준다.
     */
    /** 아직 확정·폐기하지 않은 가장 최근 계획 초안(기간이 있는 제안). 상담의 [계획 상태]가 쓴다. */
    AiProposal findLatestOpenPlanProposal(@Param("userId") Long userId);

    AiProposal findProposedByRequestKey(@Param("userId") Long userId, @Param("requestKey") String requestKey);

    /** 이 초안을 같은 조건으로 다시 만들어 대체한 열린 초안(plan_request_json.previousProposalId가 이 id). 없으면 null. */
    AiProposal findProposedReplacing(@Param("userId") Long userId, @Param("previousProposalId") Long previousProposalId);

    AiProposal findBySourceMessageIdAndUserId(
            @Param("sourceMessageId") Long sourceMessageId,
            @Param("userId") Long userId
    );
}
