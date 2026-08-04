package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * ai_proposals/ai_proposal_items 저장 전용.
 *
 * 대화 스트리밍(AiConversationService)이 끝난 뒤, 서버 검증까지 마친 ProposalItemPayload
 * 목록만 받아 짧은 트랜잭션으로 저장한다. 이 클래스에만 @Transactional을 두고, LLM 스트림
 * 소비를 포함하는 호출부에는 붙이지 않는다 — 별도 빈으로 분리해야 Spring AOP 자기호출
 * 문제 없이 트랜잭션 경계가 보장된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProposalPersistenceService {

    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Transactional
    public AiProposalResponse save(Long userId, Long conversationId, Long sourceMessageId, List<ProposalItemPayload> items) {
        AiProposal proposal = AiProposal.builder()
                .userId(userId)
                .conversationId(conversationId)
                .sourceMessageId(sourceMessageId)
                .targetScope(AiProposalTargetScope.TODAY)
                .status(AiProposalStatus.PROPOSED)
                .build();
        aiProposalMapper.insert(proposal);

        List<AiProposalItemResponse> itemResponses = new ArrayList<>();
        for (ProposalItemPayload payload : items) {
            String json = toJson(payload);

            AiProposalItem item = AiProposalItem.builder()
                    .proposalId(proposal.getProposalId())
                    .userId(userId)
                    .itemType(AiProposalItemType.EXECUTION_ITEM)
                    .originalPayload(json)
                    .status(AiProposalItemStatus.PROPOSED)
                    .build();
            aiProposalItemMapper.insert(item);

            itemResponses.add(AiProposalItemResponse.builder()
                    .proposalItemId(item.getProposalItemId())
                    .status(item.getStatus())
                    .title(payload.title())
                    .description(payload.description())
                    .expectedMinutes(payload.expectedMinutes())
                    .priority(payload.priority())
                    .targetDate(payload.targetDate())
                    .placementType(payload.placementType())
                    .scheduledStartAt(payload.scheduledStartAt())
                    .scheduledEndAt(payload.scheduledEndAt())
                    .modified(false)
                    .build());
        }

        log.info("AI 제안 저장 완료: proposalId={}, userId={}, itemCount={}",
                proposal.getProposalId(), userId, itemResponses.size());

        return AiProposalResponse.builder()
                .proposalId(proposal.getProposalId())
                .targetScope(proposal.getTargetScope())
                .status(proposal.getStatus())
                .createdAt(proposal.getCreatedAt())
                .expiresAt(proposal.getExpiresAt())
                .respondedAt(proposal.getRespondedAt())
                .items(itemResponses)
                .build();
    }

    private String toJson(ProposalItemPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("제안 payload 직렬화 실패", e);
            throw new IllegalStateException("제안 payload 직렬화 실패", e);
        }
    }
}
