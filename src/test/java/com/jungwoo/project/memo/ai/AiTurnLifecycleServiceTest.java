package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
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
import com.jungwoo.project.memo.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대화방 동시 요청 차단 + idempotency 실제 동작을 검증한다. 여기서 검증하는 것은 "가드
 * 조건이 있는 SQL을 올바른 인자로 호출하는가"이다 — 실제 MariaDB 행 잠금 자체의 동시성은
 * 순수 단위 테스트로 증명할 수 없다(별도의 통합 테스트가 필요하다).
 */
@ExtendWith(MockitoExtension.class)
class AiTurnLifecycleServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;

    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private AiConversationMapper aiConversationMapper;
    @Mock private AiProposalService aiProposalService;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;
    @Mock private ContextChangeSuggestionService contextChangeSuggestionService;

    @InjectMocks
    private AiTurnLifecycleService service;

    @Test
    void prepareTurn_throwsNotFound_whenConversationNotOwned() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("안녕", "k1")))
                .isInstanceOf(NotFoundException.class);

        verify(aiMessageMapper, never()).insert(any());
    }

    @Test
    void prepareTurn_throwsNotFound_whenConversationWasDeletedByUser() {
        // 다른 탭에 열려 있던 화면이 뒤늦게 메시지를 보내 지운 대화를 되살리면 안 된다.
        AiConversation deleted = freeConversation();
        deleted.setStatus(ConversationStatus.ARCHIVED);
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(deleted);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("안녕", "k1")))
                .isInstanceOf(NotFoundException.class);

        verify(aiMessageMapper, never()).insert(any());
        verify(aiConversationMapper, never()).acquireActiveRequest(any(), any(), any());
    }

    @Test
    void prepareTurn_throwsNotConfigured_beforeAnyDbWrite() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        when(aiConsultationClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("안녕", "k1")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED));

        verify(aiMessageMapper, never()).insert(any());
        verify(aiConversationMapper, never()).acquireActiveRequest(any(), any(), any());
    }

    @Test
    void prepareTurn_throwsUsageLimitExceeded_beforeAnyDbWrite() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.doThrow(new TooManyRequestsException(ErrorCode.AI_USAGE_LIMIT_EXCEEDED))
                .when(aiUsageLimitService).checkLimit(USER_ID);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("안녕", "k1")))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_USAGE_LIMIT_EXCEEDED));

        verify(aiMessageMapper, never()).insert(any());
        verify(aiConversationMapper, never()).acquireActiveRequest(any(), any(), any());
    }

    @Test
    void prepareTurn_throwsBadRequest_whenCreateProposalWithoutTextAndNoSourceMessageId() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());

        AiMessageRequest request = AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PROPOSAL)
                .idempotencyKey("k1")
                .build();

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void prepareTurn_replays_whenIdempotencyKeyMatchesCompletedMessage_noNewInsert() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        AiMessage existing = AiMessage.builder().messageId(500L).userId(USER_ID)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup")).thenReturn(existing);

        AiTurnLifecycleService.PreparedTurn result = service.prepareTurn(CONVERSATION_ID, USER_ID, request("아무거나", "dup"));

        assertThat(result.replay()).isTrue();
        assertThat(result.requestMessageId()).isEqualTo(500L);
        verify(aiMessageMapper, never()).insert(any());
        verify(aiConversationMapper, never()).acquireActiveRequest(any(), any(), any());
    }

    @Test
    void prepareTurn_throwsBusy_whenIdempotencyKeyMatchesProcessingMessage_noAiCall() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        AiMessage existing = AiMessage.builder().messageId(500L).userId(USER_ID)
                .status(MessageStatus.PROCESSING).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup")).thenReturn(existing);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("아무거나", "dup")))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_CONVERSATION_BUSY));

        verify(aiMessageMapper, never()).insert(any());
        verify(aiConsultationClient, never()).streamTurn(any(), any());
    }

    @Test
    void prepareTurn_throwsGenerationFailed_whenIdempotencyKeyMatchesFailedMessage_noAutoRetry() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        AiMessage existing = AiMessage.builder().messageId(500L).userId(USER_ID)
                .status(MessageStatus.FAILED).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup")).thenReturn(existing);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("아무거나", "dup")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(aiMessageMapper, never()).insert(any());
    }

    /**
     * requirement 1: 같은 idempotencyKey의 PROCESSING 메시지가 실제로 대화방의 active request와
     * 묶여 있고 아직 stale이 아니면(방금 시작) 기존처럼 BUSY로 남아야 한다 — stale 회수가
     * 먼저 실행돼도 아무것도 바꾸지 않는다.
     */
    @Test
    void prepareTurn_idempotencyProcessing_notStale_staysBusy_reclaimDoesNotTouchIt() {
        AiConversation active = freeConversation();
        active.setActiveRequestMessageId(500L);
        active.setActiveRequestStartedAt(LocalDateTime.now().minusSeconds(5)); // 방금 시작 — stale 아님
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(active);
        AiMessage existing = AiMessage.builder().messageId(500L).userId(USER_ID)
                .status(MessageStatus.PROCESSING).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup")).thenReturn(existing);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("재요청", "dup")))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_CONVERSATION_BUSY));

        verify(aiMessageMapper, never()).updateStatusIfCurrent(eq(500L), any(), any(), any());
        verify(aiMessageMapper, never()).insert(any());
    }

    /**
     * requirement 2: 같은 idempotencyKey의 PROCESSING 메시지가 실제로 stale이면, stale 회수가
     * idempotencyKey 판정보다 먼저 반영돼 그 메시지가 FAILED로 바뀐 뒤 조회된다 — 무한 BUSY가
     * 아니라 기존 FAILED 정책(AI_GENERATION_FAILED)으로 끝나고, 새 OpenAI 호출(= 새 메시지
     * 삽입)은 일어나지 않는다.
     */
    @Test
    void prepareTurn_idempotencyProcessing_stale_reclaimedFirst_thenFailedPolicy_noAutoRetry() {
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "staleLockBufferSeconds", 1);

        AiConversation stale = freeConversation();
        stale.setActiveRequestMessageId(500L);
        stale.setActiveRequestStartedAt(LocalDateTime.now().minusSeconds(30)); // 타임아웃+버퍼를 훨씬 넘김
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(stale);
        // 같은 트랜잭션 안에서 reclaim이 먼저 FAILED로 바꾼 뒤 이 조회가 그 최신 상태를 그대로
        // 읽는다고 가정한다(실제로는 방금 UPDATE한 값을 같은 트랜잭션의 SELECT가 그대로 본다).
        AiMessage existing = AiMessage.builder().messageId(500L).userId(USER_ID)
                .status(MessageStatus.FAILED).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup")).thenReturn(existing);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("재요청", "dup")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        // stale 회수가 idempotencyKey 판정보다 먼저 반영됐다.
        verify(aiMessageMapper).updateStatusIfCurrent(500L, USER_ID, MessageStatus.PROCESSING, MessageStatus.FAILED);
        verify(aiConversationMapper).releaseActiveRequest(CONVERSATION_ID, USER_ID, 500L);
        // 무한 BUSY가 아니라 FAILED 정책으로 끝났다 — 자동 재호출(새 메시지 삽입) 없음.
        verify(aiMessageMapper, never()).insert(any());
    }

    @Test
    void prepareTurn_throwsBusy_whenAnotherRequestActivelyProcessing_recentlyStarted() {
        AiConversation busy = freeConversation();
        busy.setActiveRequestMessageId(999L);
        busy.setActiveRequestStartedAt(LocalDateTime.now().minusSeconds(5)); // 방금 시작 — 아직 살아있을 가능성이 있다
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(busy);
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, request("다른 메시지", "new-key")))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_CONVERSATION_BUSY));

        verify(aiMessageMapper, never()).insert(any());
        // 살아있을 가능성이 있는 요청을 실패 처리하며 탈취하지 않는다.
        verify(aiMessageMapper, never()).updateStatusIfCurrent(eq(999L), any(), any(), any());
    }

    @Test
    void prepareTurn_reclaimsStaleLock_marksOldRequestFailed_andProceeds() {
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "staleLockBufferSeconds", 1);

        AiConversation stale = freeConversation();
        stale.setActiveRequestMessageId(999L);
        stale.setActiveRequestStartedAt(LocalDateTime.now().minusSeconds(30)); // 타임아웃+버퍼를 훨씬 넘김
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(stale);
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        AiTurnLifecycleService.PreparedTurn result = service.prepareTurn(CONVERSATION_ID, USER_ID, request("새 메시지", "new-key"));

        assertThat(result.replay()).isFalse();
        verify(aiMessageMapper).updateStatusIfCurrent(999L, USER_ID, MessageStatus.PROCESSING, MessageStatus.FAILED);
        verify(aiConversationMapper).releaseActiveRequest(CONVERSATION_ID, USER_ID, 999L);
        verify(aiMessageMapper).insert(any());
        // insert()는 mock이라 useGeneratedKeys로 messageId를 채워주지 않으므로 null일 수 있다 — any()로 매칭한다.
        verify(aiConversationMapper).acquireActiveRequest(eq(CONVERSATION_ID), eq(USER_ID), any());
    }

    @Test
    void prepareTurn_proceeds_insertsProcessingMessage_andAcquiresLock_whenFree() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        AiTurnLifecycleService.PreparedTurn result = service.prepareTurn(CONVERSATION_ID, USER_ID, request("안녕", "k1"));

        assertThat(result.replay()).isFalse();
        verify(aiMessageMapper).insert(org.mockito.ArgumentMatchers.argThat(m ->
                m.getRole() == MessageRole.USER
                        && m.getStatus() == MessageStatus.PROCESSING
                        && m.getContent().equals("안녕")
                        && m.getIdempotencyKey().equals("k1")));
        // insert()는 mock이라 useGeneratedKeys로 messageId를 채워주지 않으므로 null일 수 있다 — any()로 매칭한다.
        verify(aiConversationMapper).acquireActiveRequest(eq(CONVERSATION_ID), eq(USER_ID), any());
    }

    @Test
    void prepareTurn_usesPlaceholderContent_forCreateProposalWithoutText() {
        when(aiConversationMapper.findByIdAndUserIdForUpdate(CONVERSATION_ID, USER_ID)).thenReturn(freeConversation());
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        AiMessageRequest request = AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PROPOSAL)
                .sourceMessageId(42L)
                .idempotencyKey("k1")
                .build();

        service.prepareTurn(CONVERSATION_ID, USER_ID, request);

        verify(aiMessageMapper).insert(org.mockito.ArgumentMatchers.argThat(m ->
                m.getContent() != null && !m.getContent().isBlank()));
    }

    @Test
    void completeTurnSuccess_createsProposal_whenProposalType_andReleasesLock() {
        when(aiMessageMapper.updateStatusIfCurrent(501L, USER_ID, MessageStatus.PROCESSING, MessageStatus.COMPLETED))
                .thenReturn(1);
        when(aiProposalService.createFromItems(eq(USER_ID), eq(CONVERSATION_ID), any(), any(), any(), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(900L).items(List.of()).build());

        AiTurnLifecycleService.TurnCompletionResult result = service.completeTurnSuccess(
                CONVERSATION_ID, USER_ID, 501L, "reply", AiResponseType.PROPOSAL,
                List.of(sampleItem()), List.of(), LocalDate.now(), List.of(), List.of());

        assertThat(result.proposalResponseOrNull()).isNotNull();
        assertThat(result.proposalResponseOrNull().getProposalId()).isEqualTo(900L);
        verify(aiMessageMapper).updateStatusIfCurrent(501L, USER_ID, MessageStatus.PROCESSING, MessageStatus.COMPLETED);
        verify(aiConversationMapper).releaseActiveRequest(CONVERSATION_ID, USER_ID, 501L);
    }

    @Test
    void completeTurnSuccess_doesNotCreateProposal_whenChat() {
        when(aiMessageMapper.updateStatusIfCurrent(501L, USER_ID, MessageStatus.PROCESSING, MessageStatus.COMPLETED))
                .thenReturn(1);

        AiTurnLifecycleService.TurnCompletionResult result = service.completeTurnSuccess(
                CONVERSATION_ID, USER_ID, 501L, "reply", AiResponseType.CHAT, List.of(), List.of(), LocalDate.now(), List.of(), List.of());

        assertThat(result.proposalResponseOrNull()).isNull();
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 늦은 성공 저장 차단(핵심 방어선). requestMessageId가 더 이상 PROCESSING이 아니면(연결
     * 종료 등으로 이미 FAILED 처리됨) 가드된 선점(updateStatusIfCurrent)이 0행을 반환하고,
     * ASSISTANT/Proposal/Context 무엇도 저장되지 않아야 한다.
     */
    @Test
    void completeTurnSuccess_whenRequestNoLongerProcessing_throwsAndSkipsAllPersistence() {
        when(aiMessageMapper.updateStatusIfCurrent(501L, USER_ID, MessageStatus.PROCESSING, MessageStatus.COMPLETED))
                .thenReturn(0); // 이미 FAILED 등으로 바뀌어 선점 실패

        assertThatThrownBy(() -> service.completeTurnSuccess(
                CONVERSATION_ID, USER_ID, 501L, "뒤늦게 도착한 답변", AiResponseType.CHAT,
                List.of(), List.of(), LocalDate.now(), List.of(), List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(aiMessageMapper, never()).insert(any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any(), any());
        verify(contextChangeSuggestionService, never()).createFromSuggestions(any(), any(), any(), any());
        verify(aiConversationMapper, never()).releaseActiveRequest(any(), any(), any());
    }

    @Test
    void completeTurnFailure_marksFailedAndReleasesLock_withGuardedTransition() {
        service.completeTurnFailure(CONVERSATION_ID, USER_ID, 501L);

        verify(aiMessageMapper).updateStatusIfCurrent(501L, USER_ID, MessageStatus.PROCESSING, MessageStatus.FAILED);
        verify(aiConversationMapper).releaseActiveRequest(CONVERSATION_ID, USER_ID, 501L);
    }

    @Test
    void completeTurnFailure_doesNothing_whenRequestMessageIdNull() {
        service.completeTurnFailure(CONVERSATION_ID, USER_ID, null);

        verify(aiMessageMapper, never()).updateStatusIfCurrent(any(), any(), any(), any());
        verify(aiConversationMapper, never()).releaseActiveRequest(any(), any(), any());
    }

    private AiConversation freeConversation() {
        return AiConversation.builder()
                .conversationId(CONVERSATION_ID).userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY).status(ConversationStatus.ACTIVE)
                .build();
    }

    private AiMessageRequest request(String message, String idempotencyKey) {
        return AiMessageRequest.builder()
                .message(message)
                .requestedAction(RequestedAction.AUTO)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private ProposalItem sampleItem() {
        return new ProposalItem("제목", "설명", 30, "SHOULD",
                com.jungwoo.project.memo.execution.domain.PlacementType.DATE_ONLY, null, null,
                null, null, null, null, null);
    }
}
