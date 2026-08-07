package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiContextChangeSuggestion;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.ContextSuggestionStatus;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.ai.dto.ContextChangeSuggestion;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 장기 컨텍스트 변경 후보(AiContextChangeSuggestion)의 검증·저장·적용·거절을 전담한다.
 *
 * AI 판단(ContextChangeSuggestion)을 그대로 신뢰하지 않는다 — createFromSuggestions가
 * 연산별 계약(ADD/SUPERSEDE/MARK_STALE/ARCHIVE/CONFIRM)과 소유권을 다시 검증한 뒤에만
 * ai_context_change_suggestions에 저장한다. 이 시점에는 user_contexts를 전혀 건드리지
 * 않는다 — 실제 장기 컨텍스트는 apply()로 사용자가 명시적으로 승인했을 때만 바뀐다.
 *
 * 이 서비스는 "이사했으니 이동시간을 무효로 해야 한다" 같은 의미 판단을 하지 않는다 —
 * 그 판단은 AI의 몫이고, 여기서는 계약 형태(필수 필드·길이·소유권·상태 전이 가능 여부)만 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextChangeSuggestionService {

    private static final int MAX_SUGGESTIONS_PER_TURN = 5;
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_REASON_LENGTH = 500;

    private final UserContextMapper userContextMapper;
    private final AiContextChangeSuggestionMapper suggestionMapper;

    // ===== 생성(검증 포함) — AiTurnLifecycleService.completeTurnSuccess 트랜잭션 안에서 호출된다 =====

    /**
     * 이미 파싱된 모델 출력(raw)을 검증해 ai_context_change_suggestions에 저장한다.
     * 계약 위반이면 AiProposalService.validateAndNormalize와 같은 방식으로 조용히 고쳐 쓰지
     * 않고 턴 전체를 실패시킨다(ServiceUnavailableException) — 호출부(completeTurnSuccess)가
     * 같은 트랜잭션이므로 이미 만든 ASSISTANT 메시지·Proposal까지 함께 롤백된다.
     */
    public List<ContextSuggestionResponse> createFromSuggestions(
            Long userId, Long conversationId, Long sourceMessageId, List<ContextChangeSuggestion> raw
    ) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_SUGGESTIONS_PER_TURN) {
            log.warn("Context 변경 후보 검증 실패: 개수 초과 (count={})", raw.size());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        List<ContextSuggestionResponse> result = new ArrayList<>();
        for (ContextChangeSuggestion candidate : raw) {
            UserContext target = validateContract(userId, candidate);

            AiContextChangeSuggestion entity = AiContextChangeSuggestion.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .operation(candidate.operation())
                    .targetContextId(candidate.targetContextId())
                    .proposedContent(candidate.content())
                    .reason(candidate.reason())
                    .status(ContextSuggestionStatus.PROPOSED)
                    .build();
            suggestionMapper.insert(entity);

            result.add(toResponse(entity, target));
        }
        return result;
    }

    /** targetContextId가 있으면 소유권을 확인하고 그 Context를 반환한다(응답 표시용). */
    private UserContext validateContract(Long userId, ContextChangeSuggestion candidate) {
        if (candidate.operation() == null) {
            log.warn("Context 변경 후보 검증 실패: operation 누락");
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        if (!hasText(candidate.reason()) || candidate.reason().length() > MAX_REASON_LENGTH) {
            log.warn("Context 변경 후보 검증 실패: reason 누락 또는 길이 초과 (operation={})", candidate.operation());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        boolean hasTarget = candidate.targetContextId() != null;
        boolean hasContent = hasText(candidate.content());
        if (hasContent && candidate.content().length() > MAX_CONTENT_LENGTH) {
            log.warn("Context 변경 후보 검증 실패: content 길이 초과 (operation={})", candidate.operation());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        UserContext target = null;
        switch (candidate.operation()) {
            case ADD -> {
                if (hasTarget) {
                    log.warn("Context 변경 후보 검증 실패: ADD인데 targetContextId 존재");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                if (!hasContent) {
                    log.warn("Context 변경 후보 검증 실패: ADD인데 content 누락");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
            }
            case SUPERSEDE -> {
                if (!hasTarget) {
                    log.warn("Context 변경 후보 검증 실패: SUPERSEDE인데 targetContextId 누락");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                if (!hasContent) {
                    log.warn("Context 변경 후보 검증 실패: SUPERSEDE인데 content 누락");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                target = requireOwnedTarget(userId, candidate.targetContextId());
            }
            case MARK_STALE, ARCHIVE, CONFIRM -> {
                if (!hasTarget) {
                    log.warn("Context 변경 후보 검증 실패: {}인데 targetContextId 누락", candidate.operation());
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                if (hasContent) {
                    log.warn("Context 변경 후보 검증 실패: {}인데 content가 채워짐", candidate.operation());
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                target = requireOwnedTarget(userId, candidate.targetContextId());
            }
        }
        return target;
    }

    private UserContext requireOwnedTarget(Long userId, Long targetContextId) {
        UserContext target = userContextMapper.findByIdAndUserId(targetContextId, userId);
        if (target == null) {
            log.warn("Context 변경 후보 검증 실패: targetContextId={}가 존재하지 않거나 다른 사용자 소유", targetContextId);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        return target;
    }

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public List<ContextSuggestionResponse> listPendingByConversation(Long conversationId, Long userId) {
        return suggestionMapper.findPendingByConversationIdAndUserId(conversationId, userId).stream()
                .map(this::toResponseWithTargetLookup)
                .toList();
    }

    /** idempotency 재생용 — 이 ASSISTANT 메시지가 만든 후보(상태 무관) 전체. */
    @Transactional(readOnly = true)
    public List<ContextSuggestionResponse> findBySourceMessageId(Long sourceMessageId, Long userId) {
        return suggestionMapper.findBySourceMessageIdAndUserId(sourceMessageId, userId).stream()
                .map(this::toResponseWithTargetLookup)
                .toList();
    }

    // ===== 적용/거절 =====

    /**
     * PROPOSED 후보를 실제 user_contexts에 반영한다. 이미 APPLIED면 새로 만들지 않고 그대로
     * 반환한다(idempotent). DISMISSED에는 적용할 수 없다. 후보를 만든 시점 이후 대상 Context의
     * 상태가 바뀌어(다른 후보가 먼저 적용되는 등) 안전하게 전이할 수 없으면 conflict로 처리하고
     * 조용히 덮어쓰지 않는다.
     */
    @Transactional
    public ContextSuggestionResponse apply(Long suggestionId, Long userId) {
        AiContextChangeSuggestion suggestion = suggestionMapper.findByIdAndUserIdForUpdate(suggestionId, userId);
        if (suggestion == null) {
            throw new NotFoundException(ErrorCode.CONTEXT_SUGGESTION_NOT_FOUND);
        }
        if (suggestion.getStatus() == ContextSuggestionStatus.DISMISSED) {
            throw new ConflictException(ErrorCode.CONTEXT_SUGGESTION_ALREADY_RESOLVED);
        }
        if (suggestion.getStatus() == ContextSuggestionStatus.APPLIED) {
            return toResponseWithTargetLookup(suggestion);
        }

        LocalDateTime now = LocalDateTime.now();
        Long resultingContextId = executeOperation(suggestion, userId, now);

        int marked = suggestionMapper.markApplied(suggestionId, userId, now, resultingContextId);
        if (marked == 0) {
            log.warn("Context 변경 후보 적용 실패: 행 잠금 상태에서 예상치 못한 동시 갱신 (suggestionId={})", suggestionId);
            throw new ConflictException(ErrorCode.CONTEXT_APPLY_CONFLICT);
        }

        AiContextChangeSuggestion applied = suggestionMapper.findByIdAndUserId(suggestionId, userId);
        log.info("Context 변경 후보 적용 완료: suggestionId={}, userId={}, operation={}, resultingContextId={}",
                suggestionId, userId, suggestion.getOperation(), resultingContextId);
        return toResponseWithTargetLookup(applied);
    }

    /** 연산별로 user_contexts를 실제로 바꾸고 결과 context_id를 반환한다. 안전하게 전이 못 하면 conflict. */
    private Long executeOperation(AiContextChangeSuggestion suggestion, Long userId, LocalDateTime now) {
        return switch (suggestion.getOperation()) {
            case ADD -> {
                UserContext created = UserContext.builder()
                        .userId(userId)
                        .content(suggestion.getProposedContent())
                        .status(UserContextStatus.ACTIVE)
                        .sourceType(ContextSourceType.AI_SUGGESTION_APPROVED)
                        .sourceMessageId(suggestion.getSourceMessageId())
                        .build();
                userContextMapper.insert(created);
                yield created.getContextId();
            }
            case SUPERSEDE -> {
                int updated = userContextMapper.updateStatusIfIn(
                        suggestion.getTargetContextId(), userId,
                        List.of(UserContextStatus.ACTIVE.name(), UserContextStatus.STALE.name()),
                        UserContextStatus.SUPERSEDED);
                if (updated == 0) {
                    throw new ConflictException(ErrorCode.CONTEXT_APPLY_CONFLICT);
                }
                UserContext created = UserContext.builder()
                        .userId(userId)
                        .content(suggestion.getProposedContent())
                        .status(UserContextStatus.ACTIVE)
                        .sourceType(ContextSourceType.AI_SUGGESTION_APPROVED)
                        .sourceMessageId(suggestion.getSourceMessageId())
                        .supersedesContextId(suggestion.getTargetContextId())
                        .build();
                userContextMapper.insert(created);
                yield created.getContextId();
            }
            case MARK_STALE -> {
                int updated = userContextMapper.updateStatusIfIn(
                        suggestion.getTargetContextId(), userId,
                        List.of(UserContextStatus.ACTIVE.name()), UserContextStatus.STALE);
                if (updated == 0) {
                    throw new ConflictException(ErrorCode.CONTEXT_APPLY_CONFLICT);
                }
                yield suggestion.getTargetContextId();
            }
            case ARCHIVE -> {
                int updated = userContextMapper.updateStatusIfIn(
                        suggestion.getTargetContextId(), userId,
                        List.of(UserContextStatus.ACTIVE.name(), UserContextStatus.STALE.name()),
                        UserContextStatus.ARCHIVED);
                if (updated == 0) {
                    throw new ConflictException(ErrorCode.CONTEXT_APPLY_CONFLICT);
                }
                yield suggestion.getTargetContextId();
            }
            case CONFIRM -> {
                int updated = userContextMapper.confirmIfStale(suggestion.getTargetContextId(), userId, now);
                if (updated == 0) {
                    throw new ConflictException(ErrorCode.CONTEXT_APPLY_CONFLICT);
                }
                yield suggestion.getTargetContextId();
            }
        };
    }

    /** PROPOSED 후보를 "저장하지 않기" 처리한다. 이미 DISMISSED면 그대로(idempotent), APPLIED면 conflict. */
    @Transactional
    public ContextSuggestionResponse dismiss(Long suggestionId, Long userId) {
        AiContextChangeSuggestion suggestion = suggestionMapper.findByIdAndUserIdForUpdate(suggestionId, userId);
        if (suggestion == null) {
            throw new NotFoundException(ErrorCode.CONTEXT_SUGGESTION_NOT_FOUND);
        }
        if (suggestion.getStatus() == ContextSuggestionStatus.APPLIED) {
            throw new ConflictException(ErrorCode.CONTEXT_SUGGESTION_ALREADY_RESOLVED);
        }
        if (suggestion.getStatus() == ContextSuggestionStatus.DISMISSED) {
            return toResponseWithTargetLookup(suggestion);
        }

        LocalDateTime now = LocalDateTime.now();
        int marked = suggestionMapper.markDismissed(suggestionId, userId, now);
        if (marked == 0) {
            log.warn("Context 변경 후보 거절 실패: 행 잠금 상태에서 예상치 못한 동시 갱신 (suggestionId={})", suggestionId);
            throw new ConflictException(ErrorCode.CONTEXT_SUGGESTION_ALREADY_RESOLVED);
        }

        AiContextChangeSuggestion dismissed = suggestionMapper.findByIdAndUserId(suggestionId, userId);
        return toResponseWithTargetLookup(dismissed);
    }

    // ===== 변환 =====

    private ContextSuggestionResponse toResponseWithTargetLookup(AiContextChangeSuggestion suggestion) {
        UserContext target = suggestion.getTargetContextId() != null
                ? userContextMapper.findByIdAndUserId(suggestion.getTargetContextId(), suggestion.getUserId())
                : null;
        return toResponse(suggestion, target);
    }

    private ContextSuggestionResponse toResponse(AiContextChangeSuggestion suggestion, UserContext target) {
        return ContextSuggestionResponse.builder()
                .suggestionId(suggestion.getSuggestionId())
                .conversationId(suggestion.getConversationId())
                .sourceMessageId(suggestion.getSourceMessageId())
                .operation(suggestion.getOperation())
                .targetContextId(suggestion.getTargetContextId())
                .targetContextContent(target != null ? target.getContent() : null)
                .targetContextStatus(target != null ? target.getStatus() : null)
                .proposedContent(suggestion.getProposedContent())
                .reason(suggestion.getReason())
                .status(suggestion.getStatus())
                .resultingContextId(suggestion.getResultingContextId())
                .createdAt(suggestion.getCreatedAt())
                .resolvedAt(suggestion.getResolvedAt())
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
