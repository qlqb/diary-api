package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * AiConsultationClient는 항상 목(mock) 처리한다 — 실제 OpenAI를 부르지 않는다.
 * 대화방 잠금·idempotency·상태 전이 같은 안전 규칙 자체는 AiTurnLifecycleServiceTest에서
 * 검증하고, 여기서는 AiConversationService가 그 결과에 맞춰 정확히 한 번만 스트리밍하고
 * 재호출 없이 성공/실패를 마무리하는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long REQUEST_MESSAGE_ID = 200L;

    @Mock private AiConversationMapper aiConversationMapper;
    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private AiTurnLifecycleService aiTurnLifecycleService;
    @Mock private ContextSnapshotService contextSnapshotService;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiProposalService aiProposalService;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @InjectMocks
    private AiConversationService service;

    /** 과제 예시와 동일한 고정 시각: UTC 2026-08-05T05:30:00Z = KST 2026-08-05T14:30:00+09:00(수요일). */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-05T05:30:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUpClock() {
        // 순수 단위 테스트(@InjectMocks)는 Spring 컨텍스트 없이 @Value를 처리하지 않으므로
        // clock 빈이 주입되지 않는다 — 고정 Clock을 직접 넣어 실제 서버 시각에 의존하지 않게 한다.
        ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
    }

    @Test
    void streamAndComplete_chat_singleAiCall_noProposal() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "안녕! 오늘은 어떤 얘기부터 해볼까?\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"CHAT\",\"proposalItems\":[],\"offerAction\":null}";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("안녕", "k1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.proposalId()).isNull();
        assertThat(sink.deltas.toString()).isEqualTo("안녕! 오늘은 어떤 얘기부터 해볼까?\n");
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    @Test
    void streamAndComplete_includesCurrentTimeBlock_inSystemPrompt_onceOnly() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n{\"responseType\":\"CHAT\",\"proposalItems\":[],\"offerAction\":null}";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 뭐 해야 해?", "k-time-1"), sink);
        awaitTerminal(sink, d);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(systemPromptCaptor.capture(), userPromptCaptor.capture());
        String systemPrompt = systemPromptCaptor.getValue();
        String userPrompt = userPromptCaptor.getValue();

        assertThat(systemPrompt).contains("[현재 시간 정보]");
        assertThat(systemPrompt).contains("현재 일시: 2026-08-05T14:30:00+09:00");
        assertThat(systemPrompt).contains("사용자 시간대: Asia/Seoul");
        assertThat(systemPrompt).contains("오늘 날짜: 2026-08-05");
        assertThat(systemPrompt).contains("오늘 요일: 수요일");
        // 한 번만 포함돼야 한다(과거 메시지마다 반복 삽입 금지).
        assertThat(countOccurrences(systemPrompt, "[현재 시간 정보]")).isEqualTo(1);

        // 시간 메타데이터는 사용자 메시지 본문에 섞이지 않는다.
        assertThat(userPrompt).doesNotContain("[현재 시간 정보]");
        assertThat(userPrompt).doesNotContain("사용자 시간대");
        assertThat(userPrompt).contains("오늘 뭐 해야 해?");
    }

    @Test
    void streamAndComplete_generatesFreshTime_onEachNewRequest() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n{\"responseType\":\"CHAT\",\"proposalItems\":[],\"offerAction\":null}";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null));

        awaitTerminal(new RecordingSink(),
                service.streamAndComplete(preparedTurn(), request("첫 요청", "k-time-2a"), new RecordingSink()));

        ArgumentCaptor<String> firstCapture = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(firstCapture.capture(), any());
        assertThat(firstCapture.getValue()).contains("현재 일시: 2026-08-05T14:30:00+09:00");

        // 다음 요청 시점으로 시계를 하루 앞당긴다 — 새 요청은 새 시각을 반영해야 한다.
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-06T05:30:00Z"), ZoneOffset.UTC));

        awaitTerminal(new RecordingSink(),
                service.streamAndComplete(preparedTurn(), request("다음날 요청", "k-time-2b"), new RecordingSink()));

        ArgumentCaptor<String> secondCapture = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(2)).streamTurn(secondCapture.capture(), any());
        assertThat(secondCapture.getValue()).contains("현재 일시: 2026-08-06T14:30:00+09:00");
        assertThat(secondCapture.getValue()).contains("오늘 요일: 목요일");
    }

    @Test
    void streamAndComplete_offer_singleAiCall_noProposal() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "계획 초안을 만들어볼까?\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"OFFER\",\"proposalItems\":[],"
                + "\"offerAction\":{\"type\":\"CREATE_PROPOSAL\",\"label\":\"이 내용으로 계획 초안 만들기\"}}";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(202L), null));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("다음 주까지 배포해야 해", "k2"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.offerAction).isNotNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void streamAndComplete_proposal_singleAiCall_createsExactlyOneProposal() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "지금 상황에서 세 조각을 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"responseType\":\"PROPOSAL\",\"proposalItems\":["
                + "{\"title\":\"교재 6장 읽기\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],\"offerAction\":null}";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(900L).items(List.of()).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(203L), proposalResponse));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 짜줘", "k3"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.completed.proposalId()).isEqualTo(900L);
        assertThat(sink.proposalReady).isNotNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiTurnLifecycleService, times(1)).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void streamAndComplete_malformedStructuredJson_fallsBackToChat_withoutRecalling() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        String raw = "음, 알겠어.\n<<<AI_STRUCTURED>>>\n{이건 유효한 JSON이 아님";
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(204L), null));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("음", "k4"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void streamAndComplete_openAiError_marksFailed_releasesLock_noRetry() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any()))
                .thenReturn(Flux.error(new RuntimeException("429 insufficient_quota")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 짜줘", "k5"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
        assertThat(sink.completed).isNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any());
    }

    @Test
    void streamAndComplete_timeout_marksFailed_releasesLock_noRetry() {
        // 테스트가 실제로 90초를 기다리지 않도록 타임아웃을 1초로 줄인다.
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
        when(contextSnapshotService.buildContextBlock(any(), any(), any())).thenReturn("");
        // 절대 완료되지 않는 스트림 — .timeout()이 강제로 끊어야 한다.
        when(aiConsultationClient.streamTurn(any(), any())).thenReturn(Flux.never());

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 짜줘", "k6"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNotNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    @Test
    void replayStoredTurn_doesNotCallAiAtAll() {
        AiMessage requestMessage = AiMessage.builder()
                .messageId(300L).userId(USER_ID).conversationId(CONVERSATION_ID).build();
        AiMessage storedAssistantReply = AiMessage.builder()
                .messageId(301L).userId(USER_ID).role(MessageRole.ASSISTANT)
                .content("이미 답변했던 내용").responseType(AiResponseType.CHAT)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findByReplyToMessageIdAndUserId(300L, USER_ID)).thenReturn(storedAssistantReply);

        RecordingSink sink = new RecordingSink();
        AiConversation conversation = conversation();
        AiTurnLifecycleService.PreparedTurn replay =
                replayPreparedTurn(conversation, requestMessage);

        service.replayStoredTurn(replay, sink);

        assertThat(sink.completed.reply()).isEqualTo("이미 답변했던 내용");
        verify(aiConsultationClient, never()).streamTurn(any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getMessages_throwsNotFound_whenConversationNotOwned() {
        // findByIdAndUserId는 "존재하지 않음"과 "다른 사용자 소유"를 똑같이 null로 돌려준다 —
        // 그래서 이 하나의 예외 분기가 두 경우를 구분 없이 같은 404로 응답하게 만든다
        // (다른 사용자에게 "존재는 하지만 권한 없음"이라는 정보 자체를 흘리지 않는다).
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getMessages(CONVERSATION_ID, USER_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(aiMessageMapper, never()).findByConversationIdAndUserId(any(), any());
    }

    @Test
    void listConversations_queriesByUserId_andCleansUpTitles() {
        AiConversationResponse raw = AiConversationResponse.builder()
                .conversationId(CONVERSATION_ID)
                .title("  프로젝트 때문에\n\n   좀    막막해요   정말로 어떻게 해야 할지 모르겠어요  ")
                .lastMessageAt(LocalDateTime.of(2026, 8, 5, 10, 0))
                .pendingProposalCount(2)
                .build();
        when(aiConversationMapper.findSummariesByUserId(USER_ID)).thenReturn(new ArrayList<>(List.of(raw)));

        List<AiConversationResponse> result = service.listConversations(USER_ID);

        verify(aiConversationMapper).findSummariesByUserId(USER_ID);
        assertThat(result).hasSize(1);
        // 줄바꿈·연속 공백이 하나로 정리되고, 24자를 넘으면 말줄임표가 붙는다.
        assertThat(result.get(0).getTitle()).doesNotContain("\n");
        assertThat(result.get(0).getTitle()).endsWith("…");
        assertThat(result.get(0).getTitle().length()).isEqualTo(25); // 24자 + '…'
        assertThat(result.get(0).getPendingProposalCount()).isEqualTo(2);
    }

    @Test
    void listConversations_keepsShortTitleAsIs_withoutEllipsis() {
        AiConversationResponse raw = AiConversationResponse.builder()
                .conversationId(CONVERSATION_ID)
                .title("안녕")
                .lastMessageAt(LocalDateTime.now())
                .pendingProposalCount(0)
                .build();
        when(aiConversationMapper.findSummariesByUserId(USER_ID)).thenReturn(new ArrayList<>(List.of(raw)));

        List<AiConversationResponse> result = service.listConversations(USER_ID);

        assertThat(result.get(0).getTitle()).isEqualTo("안녕");
    }

    // ===== helpers =====

    /**
     * Flux.just/Flux.error 기반 테스트는 구독이 호출 스레드에서 동기적으로 끝나므로 사실상
     * 즉시 반환한다. Flux.never() + timeout()을 쓰는 테스트만 실제로 타이머 스레드를 기다려야
     * 해서 짧게 폴링한다(외부 라이브러리 추가 없이).
     */
    private void awaitTerminal(RecordingSink sink, Disposable disposable) {
        long deadline = System.currentTimeMillis() + 5000;
        while (sink.completed == null && sink.errorCode == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private AiConversation conversation() {
        return AiConversation.builder()
                .conversationId(CONVERSATION_ID).userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY).status(ConversationStatus.ACTIVE)
                .build();
    }

    private AiTurnLifecycleService.PreparedTurn preparedTurn() {
        return newPreparedTurn(conversation(), REQUEST_MESSAGE_ID, false, null);
    }

    private AiTurnLifecycleService.PreparedTurn replayPreparedTurn(AiConversation conversation, AiMessage replayUserMessage) {
        return newPreparedTurn(conversation, replayUserMessage.getMessageId(), true, replayUserMessage);
    }

    /** PreparedTurn 생성자는 package-private이 아니라 public record이므로 바로 new할 수 있다. */
    private AiTurnLifecycleService.PreparedTurn newPreparedTurn(
            AiConversation conversation, Long requestMessageId, boolean replay, AiMessage replayUserMessage
    ) {
        return new AiTurnLifecycleService.PreparedTurn(conversation, requestMessageId, replay, replayUserMessage);
    }

    private AiMessage assistantMessage(Long id) {
        return AiMessage.builder().messageId(id).userId(USER_ID).conversationId(CONVERSATION_ID)
                .role(MessageRole.ASSISTANT).status(MessageStatus.COMPLETED).build();
    }

    private AiMessageRequest request(String message, String idempotencyKey) {
        return AiMessageRequest.builder()
                .message(message)
                .requestedAction(RequestedAction.AUTO)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static class RecordingSink implements AiTurnEventSink {
        StringBuilder deltas = new StringBuilder();
        AiTurnCompletedPayload completed;
        ErrorCode errorCode;
        OfferAction offerAction;
        AiProposalResponse proposalReady;
        AtomicInteger startedCount = new AtomicInteger(0);
        List<String> events = new ArrayList<>();

        @Override public void onStarted(Long requestMessageId) { startedCount.incrementAndGet(); events.add("started"); }
        @Override public void onDelta(String text) { deltas.append(text); }
        @Override public void onOfferReady(OfferAction offerAction) { this.offerAction = offerAction; }
        @Override public void onProposalReady(AiProposalResponse proposal) { this.proposalReady = proposal; }
        @Override public void onCompleted(AiTurnCompletedPayload payload) { this.completed = payload; }
        @Override public void onError(ErrorCode errorCode) { this.errorCode = errorCode; }
    }
}
