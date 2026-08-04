package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 상담 한 턴의 생명주기(잠금 획득/해제, 상태 전이, 메시지·Proposal 저장)를 책임진다.
 *
 * AiConversationService는 스트림 구독(네트워크 I/O)을 포함하므로 그 메서드에 @Transactional을
 * 걸지 않는다 — 실제 DB 쓰기는 전부 이 빈의 짧은 트랜잭션 메서드에 모은다
 * (AiProposalPersistenceService와 같은 이유: 자기호출 문제 없이 트랜잭션 경계를 보장한다).
 *
 * prepareTurn()이 짧은 트랜잭션 안에서 대화방의 진행 권한을 원자적으로 획득한다
 * (SELECT ... FOR UPDATE로 같은 대화방에 대한 동시 요청을 직렬화한 뒤, 이미 다른 요청이
 * 진행 중이면 OpenAI를 부르기 전에 예외를 던진다). 이 예외는 아직 SSE 스트림을 시작하기
 * 전이므로 컨트롤러까지 그대로 전파돼 실제 HTTP 409/503으로 응답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTurnLifecycleService {

    private static final String CREATE_PROPOSAL_PLACEHOLDER_CONTENT = "(계획 초안 생성 요청)";

    private final AiMessageMapper aiMessageMapper;
    private final AiConversationMapper aiConversationMapper;
    private final AiProposalService aiProposalService;
    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;

    // 기본값을 필드 이니셜라이저에도 둔다 — 순수 단위 테스트(@InjectMocks)는 Spring 컨텍스트
    // 없이 @Value를 처리하지 않으므로, 이게 없으면 테스트에서 0초가 돼 stale 판정이 어긋난다.
    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    /** 요청 타임아웃을 넘기고도 이만큼 더 지나야 "오래된 요청"으로 보고 잠금을 회수한다. */
    @Value("${ai.conversation.stale-lock-buffer-seconds:30}")
    private int staleLockBufferSeconds = 30;

    @Transactional
    public PreparedTurn prepareTurn(Long conversationId, Long userId, AiMessageRequest request) {
        AiConversation conversation = aiConversationMapper.findByIdAndUserIdForUpdate(conversationId, userId);
        if (conversation == null) {
            throw new NotFoundException(ErrorCode.ENTITY_NOT_FOUND);
        }

        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            AiMessage existing = aiMessageMapper.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                return handleExistingIdempotentMessage(conversation, existing);
            }
        }

        boolean createProposalWithoutText = request.getRequestedAction() == RequestedAction.CREATE_PROPOSAL
                && (request.getMessage() == null || request.getMessage().isBlank());
        if (createProposalWithoutText && request.getSourceMessageId() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // AI 미설정·사용량 한도 초과는 진행 중 표시를 만들기 전에 걸러낸다 — 실패할 요청 때문에
        // 대화방을 잠그거나 고아 PROCESSING 행을 남기지 않는다.
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        aiUsageLimitService.checkLimit(userId);

        reclaimStaleLockIfAny(conversation, userId);
        if (conversation.getActiveRequestMessageId() != null) {
            // 이미 다른 요청이 진행 중이고 아직 오래되지 않았다 — OpenAI를 부르지 않고 막는다.
            throw new ConflictException(ErrorCode.AI_CONVERSATION_BUSY);
        }

        String content = createProposalWithoutText ? CREATE_PROPOSAL_PLACEHOLDER_CONTENT : request.getMessage();

        AiMessage requestMessage = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .idempotencyKey(idempotencyKey)
                .status(MessageStatus.PROCESSING)
                .build();
        aiMessageMapper.insert(requestMessage);
        aiConversationMapper.acquireActiveRequest(conversationId, userId, requestMessage.getMessageId());

        return PreparedTurn.proceed(conversation, requestMessage);
    }

    private PreparedTurn handleExistingIdempotentMessage(AiConversation conversation, AiMessage existing) {
        return switch (existing.getStatus()) {
            case COMPLETED -> PreparedTurn.replay(conversation, existing);
            // 처리 중인 동일 키 재전송: 새 OpenAI 호출 없이 "진행 중"을 일관되게 알린다.
            case PROCESSING -> throw new ConflictException(ErrorCode.AI_CONVERSATION_BUSY);
            // 이전 시도가 실패한 키로는 자동 재호출하지 않는다 — 사용자가 새 키로 다시 시도해야 한다.
            case FAILED -> throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        };
    }

    /**
     * 서버 비정상 종료 등으로 오래된 PROCESSING이 남아있을 수 있다. 설정된 타임아웃(+여유
     * 버퍼)을 넘긴 요청만 명시적으로 FAILED 처리하고 잠금을 회수한다 — 아직 살아있을 가능성이
     * 있는 요청을 임의로 탈취하지 않는다.
     */
    private void reclaimStaleLockIfAny(AiConversation conversation, Long userId) {
        Long activeMessageId = conversation.getActiveRequestMessageId();
        if (activeMessageId == null) {
            return;
        }
        LocalDateTime startedAt = conversation.getActiveRequestStartedAt();
        int staleThresholdSeconds = requestTimeoutSeconds + staleLockBufferSeconds;
        boolean stale = startedAt == null || startedAt.isBefore(LocalDateTime.now().minusSeconds(staleThresholdSeconds));
        if (!stale) {
            return;
        }

        log.warn("오래된 진행 중 요청 회수: conversationId={}, staleMessageId={}",
                conversation.getConversationId(), activeMessageId);
        aiMessageMapper.updateStatusIfCurrent(activeMessageId, userId, MessageStatus.PROCESSING, MessageStatus.FAILED);
        aiConversationMapper.releaseActiveRequest(conversation.getConversationId(), userId, activeMessageId);
        conversation.setActiveRequestMessageId(null);
        conversation.setActiveRequestStartedAt(null);
    }

    /** ASSISTANT 메시지 + (PROPOSAL이면) Proposal 저장 + USER 메시지 COMPLETED + 진행 표시 해제, 전부 한 트랜잭션. */
    @Transactional
    public TurnCompletionResult completeTurnSuccess(
            Long conversationId, Long userId, Long requestMessageId,
            String replyContent, AiResponseType responseType,
            List<ProposalItem> proposalItemsIfProposal, LocalDate targetDateIfProposal
    ) {
        AiMessage assistantMessage = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.ASSISTANT)
                .content(replyContent)
                .responseType(responseType)
                .replyToMessageId(requestMessageId)
                .status(MessageStatus.COMPLETED)
                .build();
        aiMessageMapper.insert(assistantMessage);

        AiProposalResponse proposalResponse = null;
        if (responseType == AiResponseType.PROPOSAL) {
            proposalResponse = aiProposalService.createFromItems(
                    userId, conversationId, assistantMessage.getMessageId(), proposalItemsIfProposal, targetDateIfProposal);
        }

        aiMessageMapper.updateStatusIfCurrent(requestMessageId, userId, MessageStatus.PROCESSING, MessageStatus.COMPLETED);
        aiConversationMapper.releaseActiveRequest(conversationId, userId, requestMessageId);
        aiConversationMapper.touchUpdatedAt(conversationId, userId);

        return new TurnCompletionResult(assistantMessage, proposalResponse);
    }

    /**
     * 오류·타임아웃·연결 종료 시 호출한다. requestMessageId가 여전히 PROCESSING일 때만
     * FAILED로 바꾸고 잠금을 회수한다(가드된 전이) — 완료 처리와 경합해도 한쪽만 반영된다.
     */
    @Transactional
    public void completeTurnFailure(Long conversationId, Long userId, Long requestMessageId) {
        if (requestMessageId == null) {
            return;
        }
        aiMessageMapper.updateStatusIfCurrent(requestMessageId, userId, MessageStatus.PROCESSING, MessageStatus.FAILED);
        aiConversationMapper.releaseActiveRequest(conversationId, userId, requestMessageId);
    }

    /**
     * @param conversation        소유권이 확인된 대화방
     * @param requestMessageId    이번 턴을 대표하는 USER 메시지(신규 또는 idempotency 재생 대상)
     * @param replay              true면 새로 스트리밍하지 않고 저장된 결과를 재생한다
     * @param replayUserMessage   replay=true일 때만 값을 가진다
     */
    public record PreparedTurn(AiConversation conversation, Long requestMessageId, boolean replay, AiMessage replayUserMessage) {
        static PreparedTurn proceed(AiConversation conversation, AiMessage requestMessage) {
            return new PreparedTurn(conversation, requestMessage.getMessageId(), false, null);
        }

        static PreparedTurn replay(AiConversation conversation, AiMessage existingUserMessage) {
            return new PreparedTurn(conversation, existingUserMessage.getMessageId(), true, existingUserMessage);
        }
    }

    public record TurnCompletionResult(AiMessage assistantMessage, AiProposalResponse proposalResponseOrNull) {
    }
}
