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
import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
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
    public AiProposalResponse save(Long userId, Long conversationId, Long sourceMessageId,
                                    List<ProposalItemPayload> items, List<UnavailableWindowSpec> unavailableWindows) {
        return save(userId, conversationId, sourceMessageId, items, unavailableWindows, List.of());
    }

    /**
     * 항목별 근거를 함께 저장한다. 계획 경로만 쓴다.
     *
     * <p>★ {@code itemEvidenceJson}은 {@code items}와 <b>같은 순서</b>여야 한다. 검증
     * 단계가 항목 하나당 payload 하나를 순서대로 만들거나 통째로 실패하므로 이 대응이
     * 성립한다. 그래도 길이가 다르면 근거를 하나도 붙이지 않는다 — 엉뚱한 항목에 남의
     * 근거가 붙는 것이 근거가 없는 것보다 훨씬 나쁘다.
     */
    @Transactional
    public AiProposalResponse save(Long userId, Long conversationId, Long sourceMessageId,
                                    List<ProposalItemPayload> items, List<UnavailableWindowSpec> unavailableWindows,
                                    List<String> itemEvidenceJson) {
        List<String> evidence = itemEvidenceJson == null ? List.of() : itemEvidenceJson;
        if (!evidence.isEmpty() && evidence.size() != items.size()) {
            log.error("제안 저장: 항목 {}개와 근거 {}개의 수가 달라 근거를 붙이지 않는다. userId={}",
                    items.size(), evidence.size(), userId);
            evidence = List.of();
        }
        AiProposal proposal = AiProposal.builder()
                .userId(userId)
                .conversationId(conversationId)
                .sourceMessageId(sourceMessageId)
                .targetScope(AiProposalTargetScope.TODAY)
                .unavailableWindows(toJson(unavailableWindows))
                .status(AiProposalStatus.PROPOSED)
                .build();
        aiProposalMapper.insert(proposal);

        List<AiProposalItemResponse> itemResponses = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ProposalItemPayload payload = items.get(index);
            String json = toJson(payload);

            // 조정 후보는 어느 실행 조각을 바꾸려는지(target_item_id)와 그 당시 버전
            // (base_version)을 기존 컬럼에 그대로 기록한다. base_version 덕분에 사용자가
            // 제안을 받은 뒤 그 조각을 직접 고쳤다면 적용이 409로 막힌다.
            AiProposalItem item = AiProposalItem.builder()
                    .proposalId(proposal.getProposalId())
                    .userId(userId)
                    .itemType(AiProposalItemType.EXECUTION_ITEM)
                    .originalPayload(json)
                    // 근거는 original_payload와 다른 컬럼이다 — 사용자가 고친 값이 덮어쓰는
                    // 자리와 서버가 소유하는 자리를 구조로 갈라 둔다.
                    .evidenceJson(evidence.isEmpty() ? null : evidence.get(index))
                    .targetItemId(payload.targetExecutionItemId())
                    .baseVersion(payload.targetBaseVersion())
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
                    .courseId(payload.courseId())
                    .scheduledStartAt(payload.scheduledStartAt())
                    .scheduledEndAt(payload.scheduledEndAt())
                    .deadlineAt(payload.deadlineAt())
                    .deadlineDate(payload.deadlineDate())
                    .topicId(payload.topicId())
                    .actionType(payload.actionType())
                    .doneCriteria(payload.doneCriteria())
                    .doneCriteriaSource(payload.doneCriteriaSource())
                    .sourceLocator(payload.sourceLocator())
                    .modified(false)
                    .operation(payload.effectiveOperation())
                    .targetExecutionItemId(payload.targetExecutionItemId())
                    .beforeTitle(payload.beforeTitle())
                    .beforeExpectedMinutes(payload.beforeExpectedMinutes())
                    .beforeScheduledDate(payload.beforeScheduledDate())
                    .reason(payload.reason())
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

    private String toJson(List<UnavailableWindowSpec> unavailableWindows) {
        if (unavailableWindows == null || unavailableWindows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(unavailableWindows);
        } catch (Exception e) {
            log.warn("사용 불가 시간 직렬화 실패 - 저장 없이 진행", e);
            return null;
        }
    }
}
