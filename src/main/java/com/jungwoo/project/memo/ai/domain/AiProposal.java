package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 변경안 헤더. ai_proposals 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * 모델은 변경안을 만들 뿐 DB를 직접 바꾸지 않는다.
 * 사용자가 검토·수정해 적용한 것만 execution_items에 반영된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposal {

    private Long proposalId;

    private Long userId;

    private Long conversationId;

    /**
     * 이 제안을 만든 ASSISTANT 메시지(사용자 메시지가 아니다). 한 메시지는 제안을 하나만
     * 만들 수 있다(uq_ai_proposals_source_message). 그 ASSISTANT가 어떤 사용자 요청에
     * 대한 응답인지는 ai_messages.reply_to_message_id로 이미 추적되므로, 여기서 사용자
     * 메시지를 직접 가리키면 같은 관계를 두 컬럼이 중복 보관하게 된다.
     */
    private Long sourceMessageId;

    private AiProposalTargetScope targetScope;

    /**
     * 이 제안을 만든 대화에서 사용자가 명시한 사용 불가 시간(JSON, UnavailableWindowSpec 목록).
     * AI_INFERRED 성격이며 별도 확정 저장소(ContextItem)가 아직 없어 이 제안 범위 안에서만
     * 재사용한다 — schedule-preview 재계산마다 다시 반영하기 위해 원본 그대로 보존한다.
     */
    private String unavailableWindows;

    /**
     * 계획 경로로 만들어진 제안의 기간·강도·목표 시간. 확정(confirm)이 이 값을 읽어
     * plan_versions에 복사하므로, 클라이언트가 확정 시점에 기간이나 강도를 다시 보낼 필요가
     * 없다 — 다시 보내게 하면 초안과 다른 값으로 확정될 수 있다.
     *
     * 계획 경로가 아닌 제안(Today 상담, 단건 추천)은 네 값이 모두 NULL이고, 그런 제안에
     * 계획 확정을 호출하면 400으로 거절한다.
     *
     * planTargetMinutes는 프리셋 기준선이 아니라 AI가 상황을 보고 조정한 최종값이다.
     */
    private LocalDate planStartDate;

    private LocalDate planEndDate;

    private PlanIntensity planIntensity;

    private Integer planTargetMinutes;

    private AiProposalStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime respondedAt;
}
