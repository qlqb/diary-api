package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalCreateRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.ai.dto.TodayProposal;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
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
 * AI 오늘 제안 생성/조회/적용.
 *
 * 생성 흐름: LLM 호출(트랜잭션 밖) -> 서버 검증 -> 저장(짧은 트랜잭션, AiProposalPersistenceService).
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

    private final TodayProposalGenerator proposalGenerator;
    private final AiProposalPersistenceService persistenceService;
    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final ExecutionItemService executionItemService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // ===== 생성 =====

    public AiProposalResponse create(Long userId, AiProposalCreateRequest request) {
        if (!proposalGenerator.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }

        TodayProposal generated = proposalGenerator.generate(request.getSourceText(), request.getTargetDate());
        List<ProposalItemPayload> validated = validateAndNormalize(generated, request.getTargetDate());

        return persistenceService.save(userId, validated);
    }

    private List<ProposalItemPayload> validateAndNormalize(TodayProposal generated, LocalDate targetDate) {
        if (generated == null || generated.items() == null
                || generated.items().size() < MIN_ITEMS || generated.items().size() > MAX_ITEMS) {
            log.warn("AI 제안 구조 검증 실패: 항목 개수가 범위를 벗어남");
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        List<ProposalItemPayload> result = new ArrayList<>();
        for (ProposalItem item : generated.items()) {
            if (item.title() == null || item.title().isBlank()) {
                log.warn("AI 제안 구조 검증 실패: 제목 누락");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            if (item.expectedMinutes() <= 0) {
                log.warn("AI 제안 구조 검증 실패: expectedMinutes가 양수가 아님");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            if (item.priority() == null || !VALID_PRIORITIES.contains(item.priority())) {
                log.warn("AI 제안 구조 검증 실패: priority가 유효하지 않음");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            result.add(new ProposalItemPayload(
                    item.title(), item.description(), item.expectedMinutes(), item.priority(), targetDate));
        }
        return result;
    }

    // ===== 조회 =====

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

        boolean anyModified = false;
        List<AiProposalItemResponse> responses = new ArrayList<>();
        int orderIndex = 0;

        for (AiProposalItem item : items) {
            ProposalItemPayload original = fromJson(item.getOriginalPayload());
            AiProposalApplyRequest.EditedProposalItem edit = editedById.get(item.getProposalItemId());

            String title = original.title();
            String description = original.description();
            Integer expectedMinutes = original.expectedMinutes();
            String priority = original.priority();
            boolean modified = false;

            if (edit != null) {
                String newTitle = edit.getTitle() != null ? edit.getTitle() : original.title();
                String newDescription = edit.getDescription() != null ? edit.getDescription() : original.description();
                Integer newMinutes = edit.getExpectedMinutes() != null ? edit.getExpectedMinutes() : original.expectedMinutes();
                String newPriority = edit.getPriority() != null ? edit.getPriority() : original.priority();

                if (newTitle == null || newTitle.isBlank()) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (newMinutes == null || newMinutes <= 0) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (!VALID_PRIORITIES.contains(newPriority)) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }

                modified = !Objects.equals(newTitle, original.title())
                        || !Objects.equals(newDescription, original.description())
                        || !Objects.equals(newMinutes, original.expectedMinutes())
                        || !Objects.equals(newPriority, original.priority());

                title = newTitle;
                description = newDescription;
                expectedMinutes = newMinutes;
                priority = newPriority;
            }

            anyModified = anyModified || modified;

            ExecutionItem createdItem = executionItemService.createFromApprovedProposal(
                    userId, title, description, original.targetDate(),
                    expectedMinutes, ExecutionPriority.valueOf(priority), orderIndex++, modified);

            AiProposalItemStatus newStatus = modified
                    ? AiProposalItemStatus.MODIFIED_APPLIED
                    : AiProposalItemStatus.APPLIED;
            String editedJson = modified
                    ? toJson(new ProposalItemPayload(title, description, expectedMinutes, priority, original.targetDate()))
                    : null;
            LocalDateTime respondedAt = LocalDateTime.now();

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
                    .modified(modified)
                    .createdItemId(createdItem.getExecutionItemId())
                    .build());
        }

        AiProposalStatus headerStatus = anyModified ? AiProposalStatus.MODIFIED_APPLIED : AiProposalStatus.APPLIED;
        LocalDateTime respondedAt = LocalDateTime.now();
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
