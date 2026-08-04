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
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiConsultationClient는 항상 목(mock) 처리한다 — 실제 OpenAI를 부르지 않는다.
 * Flux.just(...)는 subscribe() 시 동일 스레드에서 동기적으로 흘러가므로 별도 대기 없이 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;

    @Mock private AiConversationMapper aiConversationMapper;
    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private AiMessagePersistenceService aiMessagePersistenceService;
    @Mock private ContextSnapshotService contextSnapshotService;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiProposalService aiProposalService;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @InjectMocks
    private AiConversationService service;

    @Test
    void handleMessage_chat_hasReply_noProposal_noProposalRow() {
        setUpActiveConversation();
        setUpUserMessageSaved();
        String raw = "안녕! 오늘은 어떤 얘기부터 해볼까?\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"CHAT\",\"proposalItems\":[],\"offerAction\":null}";
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiMessagePersistenceService.saveAssistantMessage(any(), any(), any(), any(), any()))
                .thenReturn(AiMessage.builder().messageId(201L).build());

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID, request("안녕", RequestedAction.AUTO, null, "k1"), sink);

        assertThat(sink.completed).isNotNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.proposalId()).isNull();
        assertThat(sink.deltas.toString()).isEqualTo("안녕! 오늘은 어떤 얘기부터 해볼까?\n");
        assertThat(sink.errorCode).isNull();
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void handleMessage_offer_showsAction_noProposalRow() {
        setUpActiveConversation();
        setUpUserMessageSaved();
        String raw = "계획 초안을 만들어볼까?\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"OFFER\",\"proposalItems\":[],"
                + "\"offerAction\":{\"type\":\"CREATE_PROPOSAL\",\"label\":\"이 내용으로 계획 초안 만들기\"}}";
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiMessagePersistenceService.saveAssistantMessage(any(), any(), any(), any(), any()))
                .thenReturn(AiMessage.builder().messageId(202L).build());

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID,
                request("다음 주까지 배포해야 하고 평일 저녁엔 알바야", RequestedAction.AUTO, null, "k2"), sink);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.offerAction).isNotNull();
        assertThat(sink.offerAction.type()).isEqualTo("CREATE_PROPOSAL");
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void handleMessage_proposal_createsProposal_andEmitsProposalReady() {
        setUpActiveConversation();
        setUpUserMessageSaved();
        String raw = "지금 상황에서 세 조각을 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"PROPOSAL\",\"proposalItems\":["
                + "{\"title\":\"교재 6장 읽기\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],\"offerAction\":null}";
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiProposalService.createFromItems(eq(USER_ID), eq(CONVERSATION_ID), anyLong(), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(900L).items(List.of()).build());
        when(aiMessagePersistenceService.saveAssistantMessage(any(), any(), any(), any(), any()))
                .thenReturn(AiMessage.builder().messageId(203L).build());

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID, request("계획 짜줘", RequestedAction.AUTO, null, "k3"), sink);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.completed.proposalId()).isEqualTo(900L);
        assertThat(sink.proposalReady).isNotNull();
        verify(aiProposalService).createFromItems(eq(USER_ID), eq(CONVERSATION_ID), anyLong(), any(), any());
    }

    @Test
    void handleMessage_malformedStructuredJson_fallsBackToChat_withoutThrowing() {
        setUpActiveConversation();
        setUpUserMessageSaved();
        String raw = "음, 알겠어.\n<<<AI_STRUCTURED>>>\n{이건 유효한 JSON이 아님";
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiMessagePersistenceService.saveAssistantMessage(any(), any(), any(), any(), any()))
                .thenReturn(AiMessage.builder().messageId(204L).build());

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID, request("음", RequestedAction.AUTO, null, "k4"), sink);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void handleMessage_sameIdempotencyKey_doesNotCallAiAgain_andReplaysStoredTurn() {
        AiConversation conversation = AiConversation.builder()
                .conversationId(CONVERSATION_ID).userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY).status(ConversationStatus.ACTIVE)
                .build();
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(conversation);

        AiMessage existingUserMessage = AiMessage.builder().messageId(300L).userId(USER_ID).build();
        when(aiMessageMapper.findByUserIdAndIdempotencyKey(USER_ID, "dup-key")).thenReturn(existingUserMessage);
        AiMessage storedAssistantReply = AiMessage.builder()
                .messageId(301L).userId(USER_ID).role(MessageRole.ASSISTANT)
                .content("이미 답변했던 내용").responseType(AiResponseType.CHAT)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findNextAssistantReply(CONVERSATION_ID, 300L)).thenReturn(storedAssistantReply);

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID, request("아무거나", RequestedAction.AUTO, null, "dup-key"), sink);

        assertThat(sink.completed.reply()).isEqualTo("이미 답변했던 내용");
        verify(aiConsultationClient, never()).streamTurn(any(), any());
        verify(aiMessagePersistenceService, never()).saveUserMessage(any(), any(), any(), any());
    }

    @Test
    void handleMessage_streamError_emitsMessageError_andDoesNotCreateProposal() {
        setUpActiveConversation();
        setUpUserMessageSaved();
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any()))
                .thenReturn(Flux.error(new RuntimeException("429 insufficient_quota")));

        RecordingSink sink = new RecordingSink();
        service.handleMessage(CONVERSATION_ID, USER_ID, request("계획 짜줘", RequestedAction.AUTO, null, "k5"), sink);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
        assertThat(sink.completed).isNull();
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
        verify(aiMessagePersistenceService, never()).saveAssistantMessage(any(), any(), any(), any(), any());
    }

    // ===== helpers =====

    private void setUpActiveConversation() {
        AiConversation conversation = AiConversation.builder()
                .conversationId(CONVERSATION_ID).userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY).status(ConversationStatus.ACTIVE)
                .build();
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(conversation);
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
    }

    private void setUpUserMessageSaved() {
        when(aiMessagePersistenceService.saveUserMessage(any(), any(), any(), any()))
                .thenReturn(AiMessage.builder().messageId(200L).build());
    }

    private AiMessageRequest request(String message, RequestedAction action, Long sourceMessageId, String idempotencyKey) {
        return AiMessageRequest.builder()
                .message(message)
                .requestedAction(action)
                .sourceMessageId(sourceMessageId)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static class RecordingSink implements AiTurnEventSink {
        StringBuilder deltas = new StringBuilder();
        AiTurnCompletedPayload completed;
        ErrorCode errorCode;
        OfferAction offerAction;
        AiProposalResponse proposalReady;
        List<String> events = new ArrayList<>();

        @Override public void onStarted() { events.add("started"); }
        @Override public void onDelta(String text) { deltas.append(text); }
        @Override public void onOfferReady(OfferAction offerAction) { this.offerAction = offerAction; }
        @Override public void onProposalReady(AiProposalResponse proposal) { this.proposalReady = proposal; }
        @Override public void onCompleted(AiTurnCompletedPayload payload) { this.completed = payload; }
        @Override public void onError(ErrorCode errorCode) { this.errorCode = errorCode; }
    }
}
