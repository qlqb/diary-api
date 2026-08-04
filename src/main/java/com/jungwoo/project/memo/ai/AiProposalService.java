package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 제안(Proposal) 저장·조회·적용.
 *
 * 생성은 더 이상 이 서비스가 LLM을 직접 부르지 않는다 — AiConversationService가 대화 한 턴을
 * 스트리밍하고 구조화 결과를 파싱한 뒤, PROPOSAL로 판단됐을 때만 createFromItems()를 부른다.
 * 적용 흐름: 행 잠금(SELECT ... FOR UPDATE) -> 상태 재확인 -> execution_items 생성 -> 상태 갱신,
 *          전부 하나의 트랜잭션.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProposalService {

    private static final Set<String> VALID_PRIORITIES = Set.of("MUST", "SHOULD", "OPTIONAL");
    private static final int MIN_ITEMS = 1;
    private static final int MAX_ITEMS = 5;
    private static final int MIN_EXPECTED_MINUTES = 5;
    private static final int MAX_EXPECTED_MINUTES = 120;

    private final AiProposalPersistenceService persistenceService;
    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final ExecutionItemService executionItemService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // ===== 생성 (PROPOSAL 턴에서만 호출) =====

    /**
     * 이미 파싱된 모델 출력(items)을 검증해 ai_proposals/ai_proposal_items로 저장한다.
     * CHAT/OFFER 턴에서는 절대 호출되지 않는다 — 항목 1~5개 검증은 여기, PROPOSAL 한 곳에만 있다.
     */
    public AiProposalResponse createFromItems(
            Long userId, Long conversationId, Long sourceMessageId,
            List<ProposalItem> items, LocalDate targetDate
    ) {
        List<ProposalItemPayload> validated = validateAndNormalize(items, targetDate);
        return persistenceService.save(userId, conversationId, sourceMessageId, validated);
    }

    private List<ProposalItemPayload> validateAndNormalize(List<ProposalItem> items, LocalDate targetDate) {
        if (items == null || items.size() < MIN_ITEMS || items.size() > MAX_ITEMS) {
            log.warn("AI 제안 구조 검증 실패: 항목 개수가 범위를 벗어남");
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        List<ProposalItemPayload> result = new ArrayList<>();
        for (ProposalItem item : items) {
            if (item.title() == null || item.title().isBlank()) {
                log.warn("AI 제안 구조 검증 실패: 제목 누락");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            if (item.expectedMinutes() == null
                    || item.expectedMinutes() < MIN_EXPECTED_MINUTES
                    || item.expectedMinutes() > MAX_EXPECTED_MINUTES) {
                log.warn("AI 제안 구조 검증 실패: expectedMinutes가 유효 범위를 벗어남");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            if (item.priority() == null || !VALID_PRIORITIES.contains(item.priority())) {
                log.warn("AI 제안 구조 검증 실패: priority가 유효하지 않음");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            PlacementType placementType = item.placementType() != null ? item.placementType() : PlacementType.DATE_ONLY;
            LocalDateTime scheduledStartAt = null;
            LocalDateTime scheduledEndAt = null;

            if (placementType == PlacementType.TIME_FIXED) {
                if (item.startTime() == null || item.endTime() == null) {
                    log.warn("AI 제안 구조 검증 실패: TIME_FIXED인데 시작/종료 시각 누락");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                if (!item.endTime().isAfter(item.startTime())) {
                    log.warn("AI 제안 구조 검증 실패: 종료 시각이 시작 시각보다 이후가 아님");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                scheduledStartAt = LocalDateTime.of(targetDate, item.startTime());
                scheduledEndAt = LocalDateTime.of(targetDate, item.endTime());
            } else if (item.startTime() != null || item.endTime() != null) {
                log.warn("AI 제안 구조 검증 실패: DATE_ONLY인데 시각이 채워짐");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            result.add(new ProposalItemPayload(
                    item.title(), item.description(), item.expectedMinutes(), item.priority(), targetDate,
                    placementType, scheduledStartAt, scheduledEndAt));
        }
        return result;
    }

    // ===== 조회 =====

    /** 이 ASSISTANT 메시지가 만든 제안을 조회한다. 없으면 null(예외 아님 — CHAT/OFFER 메시지는 정상적으로 없다). */
    @Transactional(readOnly = true)
    public AiProposalResponse findBySourceMessageId(Long assistantMessageId, Long userId) {
        AiProposal proposal = aiProposalMapper.findBySourceMessageIdAndUserId(assistantMessageId, userId);
        if (proposal == null) {
            return null;
        }
        return get(proposal.getProposalId(), userId);
    }

    @Transactional(readOnly = true)
    public AiProposalResponse get(Long proposalId, Long userId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }

        List<AiProposalItem> items = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId);
        List<AiProposalItemResponse> itemResponses = items.stream()
                .map(this::toItemResponse)
                .toList();

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

    // ===== 전체 적용 =====

    @Transactional
    public AiProposalResponse apply(Long proposalId, Long userId, AiProposalApplyRequest request) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }

        List<AiProposalItem> items = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId);
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }

        Map<Long, AiProposalApplyRequest.EditedProposalItem> editedById = indexAndValidateEditedItems(request, items);
        Set<Long> excludedIds = request.getExcludedItemIds() != null
                ? new HashSet<>(request.getExcludedItemIds())
                : Set.of();
        validateExcludedIds(excludedIds, items);

        if (excludedIds.size() == items.size()) {
            // 전부 제외하면 적용할 것이 없다 — 빈 묶음을 APPLIED로 만들지 않는다.
            throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
        }

        LocalDate targetDate = fromJson(items.get(0).getOriginalPayload()).targetDate();
        int orderIndex = executionItemService.nextOrderIndexStart(userId, targetDate);

        boolean anyModified = false;
        List<AiProposalItemResponse> responses = new ArrayList<>();
        LocalDateTime respondedAt = LocalDateTime.now();

        for (AiProposalItem item : items) {
            if (excludedIds.contains(item.getProposalItemId())) {
                aiProposalItemMapper.updateAfterApply(
                        item.getProposalItemId(), userId, AiProposalItemStatus.DISMISSED,
                        null, null, null, respondedAt);
                responses.add(toDismissedResponse(item));
                continue;
            }

            ProposalItemPayload original = fromJson(item.getOriginalPayload());
            AiProposalApplyRequest.EditedProposalItem edit = editedById.get(item.getProposalItemId());

            String title = original.title();
            String description = original.description();
            Integer expectedMinutes = original.expectedMinutes();
            String priority = original.priority();
            PlacementType placementType = original.placementType();
            LocalDateTime scheduledStartAt = original.scheduledStartAt();
            LocalDateTime scheduledEndAt = original.scheduledEndAt();
            boolean modified = false;

            if (edit != null) {
                String newTitle = edit.getTitle() != null ? edit.getTitle() : original.title();
                String newDescription = edit.getDescription() != null ? edit.getDescription() : original.description();
                Integer newMinutes = edit.getExpectedMinutes() != null ? edit.getExpectedMinutes() : original.expectedMinutes();
                String newPriority = edit.getPriority() != null ? edit.getPriority() : original.priority();
                PlacementType newPlacementType = edit.getPlacementType() != null ? edit.getPlacementType() : original.placementType();
                LocalDateTime newStart = edit.getPlacementType() != null ? edit.getScheduledStartAt() : original.scheduledStartAt();
                LocalDateTime newEnd = edit.getPlacementType() != null ? edit.getScheduledEndAt() : original.scheduledEndAt();

                if (newTitle == null || newTitle.isBlank()) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (newMinutes == null || newMinutes <= 0) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (!VALID_PRIORITIES.contains(newPriority)) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                // placementType/시각 무결성은 execution_items 생성 시 validatePlacement가 최종 확인한다.

                modified = !Objects.equals(newTitle, original.title())
                        || !Objects.equals(newDescription, original.description())
                        || !Objects.equals(newMinutes, original.expectedMinutes())
                        || !Objects.equals(newPriority, original.priority())
                        || !Objects.equals(newPlacementType, original.placementType())
                        || !Objects.equals(newStart, original.scheduledStartAt())
                        || !Objects.equals(newEnd, original.scheduledEndAt());

                title = newTitle;
                description = newDescription;
                expectedMinutes = newMinutes;
                priority = newPriority;
                placementType = newPlacementType;
                scheduledStartAt = newStart;
                scheduledEndAt = newEnd;
            }

            anyModified = anyModified || modified;

            ExecutionItem createdItem = executionItemService.createFromApprovedProposal(
                    userId, title, description, original.targetDate(),
                    expectedMinutes, ExecutionPriority.valueOf(priority), orderIndex++, modified,
                    placementType, scheduledStartAt, scheduledEndAt);

            AiProposalItemStatus newStatus = modified
                    ? AiProposalItemStatus.MODIFIED_APPLIED
                    : AiProposalItemStatus.APPLIED;
            String editedJson = modified
                    ? toJson(new ProposalItemPayload(title, description, expectedMinutes, priority,
                            original.targetDate(), placementType, scheduledStartAt, scheduledEndAt))
                    : null;

            aiProposalItemMapper.updateAfterApply(
                    item.getProposalItemId(), userId, newStatus, editedJson,
                    AiProposalItemType.EXECUTION_ITEM.name(), createdItem.getExecutionItemId(), respondedAt);

            responses.add(AiProposalItemResponse.builder()
                    .proposalItemId(item.getProposalItemId())
                    .status(newStatus)
                    .title(title)
                    .description(description)
                    .expectedMinutes(expectedMinutes)
                    .priority(priority)
                    .targetDate(original.targetDate())
                    .placementType(placementType)
                    .scheduledStartAt(scheduledStartAt)
                    .scheduledEndAt(scheduledEndAt)
                    .modified(modified)
                    .createdItemId(createdItem.getExecutionItemId())
                    .build());
        }

        AiProposalStatus headerStatus = anyModified ? AiProposalStatus.MODIFIED_APPLIED : AiProposalStatus.APPLIED;
        aiProposalMapper.updateStatusAndRespondedAt(proposalId, userId, headerStatus, respondedAt);

        log.info("AI 제안 적용 완료: proposalId={}, userId={}, status={}", proposalId, userId, headerStatus);

        return AiProposalResponse.builder()
                .proposalId(proposalId)
                .targetScope(proposal.getTargetScope())
                .status(headerStatus)
                .createdAt(proposal.getCreatedAt())
                .expiresAt(proposal.getExpiresAt())
                .respondedAt(respondedAt)
                .items(responses)
                .build();
    }

    private void validateExcludedIds(Set<Long> excludedIds, List<AiProposalItem> items) {
        if (excludedIds.isEmpty()) {
            return;
        }
        Set<Long> validIds = new HashSet<>();
        for (AiProposalItem item : items) {
            validIds.add(item.getProposalItemId());
        }
        for (Long excludedId : excludedIds) {
            if (!validIds.contains(excludedId)) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
        }
    }

    private AiProposalItemResponse toDismissedResponse(AiProposalItem item) {
        ProposalItemPayload payload = fromJson(item.getOriginalPayload());
        return AiProposalItemResponse.builder()
                .proposalItemId(item.getProposalItemId())
                .status(AiProposalItemStatus.DISMISSED)
                .title(payload.title())
                .description(payload.description())
                .expectedMinutes(payload.expectedMinutes())
                .priority(payload.priority())
                .targetDate(payload.targetDate())
                .placementType(payload.placementType())
                .scheduledStartAt(payload.scheduledStartAt())
                .scheduledEndAt(payload.scheduledEndAt())
                .modified(false)
                .createdItemId(null)
                .build();
    }

    private Map<Long, AiProposalApplyRequest.EditedProposalItem> indexAndValidateEditedItems(
            AiProposalApplyRequest request, List<AiProposalItem> items
    ) {
        Map<Long, AiProposalApplyRequest.EditedProposalItem> editedById = new HashMap<>();
        if (request.getEditedItems() == null || request.getEditedItems().isEmpty()) {
            return editedById;
        }

        Set<Long> validIds = new HashSet<>();
        for (AiProposalItem item : items) {
            validIds.add(item.getProposalItemId());
        }

        Set<Long> seen = new HashSet<>();
        for (AiProposalApplyRequest.EditedProposalItem edit : request.getEditedItems()) {
            if (edit.getProposalItemId() == null || !validIds.contains(edit.getProposalItemId())) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
            if (!seen.add(edit.getProposalItemId())) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
            editedById.put(edit.getProposalItemId(), edit);
        }
        return editedById;
    }

    private AiProposalItemResponse toItemResponse(AiProposalItem item) {
        ProposalItemPayload effective = item.getEditedPayload() != null
                ? fromJson(item.getEditedPayload())
                : fromJson(item.getOriginalPayload());

        return AiProposalItemResponse.builder()
                .proposalItemId(item.getProposalItemId())
                .status(item.getStatus())
                .title(effective.title())
                .description(effective.description())
                .expectedMinutes(effective.expectedMinutes())
                .priority(effective.priority())
                .targetDate(effective.targetDate())
                .placementType(effective.placementType())
                .scheduledStartAt(effective.scheduledStartAt())
                .scheduledEndAt(effective.scheduledEndAt())
                .modified(item.getEditedPayload() != null)
                .createdItemId(item.getCreatedItemId())
                .build();
    }

    private String toJson(ProposalItemPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("제안 payload 직렬화 실패", e);
        }
    }

    private ProposalItemPayload fromJson(String json) {
        try {
            return objectMapper.readValue(json, ProposalItemPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("제안 payload 역직렬화 실패", e);
        }
    }
}
