package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.ContextChangeOperation;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.ContextChangeSuggestion;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.ai.dto.PeriodPlanRequest;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiConsultationClient는 항상 목(mock) 처리한다 — 실제 OpenAI를 부르지 않는다.
 * 대화방 잠금·idempotency·상태 전이 같은 안전 규칙 자체는 AiTurnLifecycleServiceTest에서
 * 검증하고, 여기서는 AiConversationService가 그 결과에 맞춰 정확히 한 번만 스트리밍하고
 * 재호출 없이 성공/실패를 마무리하는지를 검증한다.
 *
 * 계획 초안 생성 권한은 requestedAction=CREATE_PROPOSAL에만 있다. 모델의 decision은 화면
 * 상태를 직접 정하지 않으므로, 아래 테스트는 "모델이 무엇을 반환했는가"와 "서버가 실제로
 * 무엇을 저장/전송했는가"를 항상 구분해서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long REQUEST_MESSAGE_ID = 200L;

    private static final String AUTO_OFFER_REPLY = "말해준 내용을 바탕으로 계획 초안을 만들어볼까요?";
    private static final String DEFAULT_OFFER_LABEL = "이 내용으로 계획 초안 만들기";

    @Mock private AiConversationMapper aiConversationMapper;
    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private AiTurnLifecycleService aiTurnLifecycleService;
    @Mock private ContextSnapshotService contextSnapshotService;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiProposalService aiProposalService;
    @Mock private AiUsageLimitService aiUsageLimitService;
    @Mock private ContextChangeSuggestionService contextChangeSuggestionService;
    @Mock private ScheduleSuggestionService scheduleSuggestionService;
    @Mock private AiWorkspaceContextBuilder aiWorkspaceContextBuilder;
    @Mock private CourseService courseService;
    @Mock private com.jungwoo.project.memo.plan.PlanDraftService planDraftService;
    @Mock private com.jungwoo.project.memo.ai.draft.AiConversationDraftMapper aiConversationDraftMapper;
    @Mock private com.jungwoo.project.memo.ai.draft.DraftFactsService draftFactsService;

    /**
     * 기본 기간 조회와 기간 지정 조회를 같은 값으로 세운다. TurnFacts가 기간에 따라 둘 중
     * 하나를 부른다 — 한쪽만 세우면 판정이 null 사실을 보게 된다.
     */
    private void stubDraftFacts(com.jungwoo.project.memo.ai.draft.DraftFacts facts) {
        lenient().when(draftFactsService.collect(eq(USER_ID), any())).thenReturn(facts);
        lenient().when(draftFactsService.collect(eq(USER_ID), any(), any(), any())).thenReturn(facts);
    }

    @InjectMocks
    private AiConversationService service;

    /** 과제 예시와 동일한 고정 시각: UTC 2026-08-05T05:30:00Z = KST 2026-08-05T14:30:00+09:00(수요일). */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-05T05:30:00Z"), ZoneOffset.UTC);

    /** FIXED_CLOCK 기준의 "오늘". CREATE_PROPOSAL 요청 기간의 기본값이다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);

    @BeforeEach
    void setUpClock() {
        // 순수 단위 테스트(@InjectMocks)는 Spring 컨텍스트 없이 @Value를 처리하지 않으므로
        // clock 빈이 주입되지 않는다 — 고정 Clock을 직접 넣어 실제 서버 시각에 의존하지 않게 한다.
        ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
        // 화면 상태 블록은 이 테스트의 관심사가 아니다 — 별도 테스트에서 다루고 여기서는 비운다.
        lenient().when(aiWorkspaceContextBuilder.build(any(), any(), any(), any())).thenReturn("");
    }

    // ===== 기본 스트리밍/시간 블록 =====

    @Test
    void streamAndComplete_chat_singleAiCall_noProposal() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "안녕! 오늘은 어떤 얘기부터 해볼까?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("안녕", "k1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.proposalId()).isNull();
        assertThat(sink.deltas.toString()).isEqualTo("안녕! 오늘은 어떤 얘기부터 해볼까?\n");
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    @Test
    void streamAndComplete_includesCurrentTimeBlock_andAutoModeBlock() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 뭐 해야 해?", "k-time-1"), sink);
        awaitTerminal(sink, d);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(systemPromptCaptor.capture(), userPromptCaptor.capture(), any(), any());
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
        // AUTO 요청은 [요청 모드] 블록으로 PROPOSAL_READY가 금지돼 있음을 프롬프트에서부터 못박는다.
        assertThat(userPrompt).contains("[요청 모드]");
        assertThat(userPrompt).contains("AUTO");
        assertThat(userPrompt).contains("PROPOSAL_READY");
        // 핵심 정보가 부족하면 OFFER보다 ASK_CLARIFICATION을 먼저 고려하라는 판단 기준도
        // AUTO 모드 블록에서부터 강조된다(성급한 OFFER_PROPOSAL 회귀 방지).
        assertThat(userPrompt).contains("ASK_CLARIFICATION을 먼저 고려한다");
    }

    @Test
    void streamAndComplete_generatesFreshTime_onEachNewRequest() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        awaitTerminal(new RecordingSink(),
                service.streamAndComplete(preparedTurn(), request("첫 요청", "k-time-2a"), new RecordingSink()));

        ArgumentCaptor<String> firstCapture = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(firstCapture.capture(), any(), any(), any());
        assertThat(firstCapture.getValue()).contains("현재 일시: 2026-08-05T14:30:00+09:00");

        // 다음 요청 시점으로 시계를 하루 앞당긴다 — 새 요청은 새 시각을 반영해야 한다.
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-06T05:30:00Z"), ZoneOffset.UTC));

        awaitTerminal(new RecordingSink(),
                service.streamAndComplete(preparedTurn(), request("다음날 요청", "k-time-2b"), new RecordingSink()));

        ArgumentCaptor<String> secondCapture = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(2)).streamTurn(secondCapture.capture(), any(), any(), any());
        assertThat(secondCapture.getValue()).contains("현재 일시: 2026-08-06T14:30:00+09:00");
        assertThat(secondCapture.getValue()).contains("오늘 요일: 목요일");
    }

    /**
     * 컨텍스트 배분 자체(최근 대화/장기 컨텍스트/이전 요약)는 ContextSnapshotServiceTest가
     * 검증한다 — 여기서는 AiConversationService가 "전체 입력 예산 - 현재 사용자 메시지 길이"를
     * 정확히 계산해 그 서비스에 넘기는지만 확인한다. 이 로직 때문에 현재 사용자 메시지 자체가
     * 잘리는 일은 없다(메시지는 buildContextBlock이 아니라 사용자 프롬프트에 그대로 붙는다).
     */
    @Test
    void streamAndComplete_passesCurrentMessageDeductedBudget_toContextSnapshotService() {
        ReflectionTestUtils.setField(service, "maxInputTokens", 100); // maxChars = 100*4 = 400
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        String userMessage = "가".repeat(50);
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request(userMessage, "k-budget-1"), sink);
        awaitTerminal(sink, d);

        ArgumentCaptor<Integer> budgetCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(contextSnapshotService).buildContextBlock(any(), any(), any(), budgetCaptor.capture(), any());
        assertThat(budgetCaptor.getValue()).isEqualTo(400 - 50);
        // 현재 사용자 메시지는 buildContextBlock과 무관하게 사용자 프롬프트에 원문 그대로 남는다.
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(any(), userPromptCaptor.capture(), any(), any());
        assertThat(userPromptCaptor.getValue()).contains(userMessage);
    }

    /**
     * prepareTurn()이 스트리밍 시작 전에 현재 사용자 메시지를 이미 PROCESSING으로 ai_messages에
     * 저장해두므로, "최근 대화" 조회에서 그 메시지 id(requestMessageId)를 명시적으로 제외해야
     * buildUserPrompt의 "사용자 상담 원문"과 중복되지 않는다. 실제 SQL 제외 동작 자체는
     * AiMessageMapperTest(실제 DB)가 증명하고, 여기서는 이 서비스가 그 id를
     * contextSnapshotService에 정확히 전달하는지 + 최종 user prompt에 현재 메시지가 정확히
     * 한 번만 나타나는지를 확인한다.
     */
    @Test
    void streamAndComplete_excludesCurrentRequestMessageId_fromContextSnapshot_andIncludesItOnceInPrompt() {
        // buildContextBlock이 이미 현재 메시지를 제외한 결과를 돌려준다고 가정한다(정상 동작 시뮬레이션).
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any()))
                .thenReturn("[최근 대화 (최대 6개)]\n사용자: 이전에 나눈 얘기\n");
        String raw = "알겠어요!\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        String currentMessage = "나 이사해서 이제 출퇴근이 20분 걸려";
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request(currentMessage, "k-dup-1"), sink);
        awaitTerminal(sink, d);

        // REQUEST_MESSAGE_ID(prepareTurn이 이미 저장해둔 현재 요청 메시지 id)가 그대로 전달돼야 한다.
        verify(contextSnapshotService).buildContextBlock(any(), any(), any(), anyInt(), eq(REQUEST_MESSAGE_ID));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(any(), userPromptCaptor.capture(), any(), any());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt).contains("이전에 나눈 얘기");
        assertThat(countOccurrences(userPrompt, currentMessage)).isEqualTo(1);
    }

    @Test
    void streamAndComplete_malformedStructuredJson_autoFallsBackToChat_withoutRecalling() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "음, 알겠어.\n<<<AI_STRUCTURED>>>\n{이건 유효한 JSON이 아님";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(204L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("음", "k4"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo("음, 알겠어.");
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());
    }

    // ===== AUTO 상태 전이(모델의 decision을 화면 상태로 그대로 쓰지 않는다) =====

    @Test
    void auto_decisionChat_resolvesToChat_noProposalSaved() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "천천히 얘기해보자.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"proposalItems\":[],\"unavailableWindows\":[],"
                + "\"periodStartDate\":null,\"periodEndDate\":null}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(300L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 많이 피곤해", "k-auto-chat"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo("천천히 얘기해보자.");
        assertThat(sink.proposalReady).isNull();
        assertThat(sink.offerAction).isNull();
    }

    @Test
    void auto_decisionAskClarification_repliesWithClarifyingQuestion_asChat() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "언제 끝나는지부터 확인할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"알바는 몇 시에 끝나나요?\","
                + "\"missingInformation\":[\"알바 종료 시각\"],\"proposalItems\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(301L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("새벽 2시쯤 자고 싶고, 오후 5시에 알바가 있어. 4시에는 출발해야 해.", "k-auto-ask"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo("알바는 몇 시에 끝나나요?");
        assertThat(sink.proposalReady).isNull();
        assertThat(sink.completed.proposalItems()).isEmpty();
    }

    /**
     * docs/product/09-ai-consultation-regression-cases.md CASE-001 회귀 계약.
     *
     * 실제 모델이 이 자연어 입력에 대해 항상 ASK_CLARIFICATION을 고를 것이라는 것을 이
     * Mockito 테스트로 "증명"할 수는 없다(그건 SYSTEM_PROMPT 판단 기준 + 실제 OpenAI
     * 검증의 몫이다). 여기서 검증하는 것은: 모델이 ASK_CLARIFICATION을 반환했을 때 서버가
     * 그 결과를 정확히 CHAT(clarifyingQuestion을 reply로) 계약으로 처리하고, OFFER 버튼도
     * PROPOSAL_READY도 절대 만들지 않는다는 것이다 — 이전 회귀(성급한 OFFER_PROPOSAL)가
     * 다시 나타나도 서버 계약 자체는 깨지지 않는다는 안전망.
     */
    @Test
    void auto_regressionCase001_planIntentButMissingCoreTimingInfo_serverHandlesAskContract_notOffer() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "프로젝트 수정을 오늘 더 진행하려는 거네. 오늘 몇 시쯤까지 작업하고, "
                + "몇 시쯤 잘 생각이야?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":"
                + "\"오늘 몇 시쯤까지 작업하고, 몇 시쯤 잘 생각이야?\","
                + "\"missingInformation\":[\"작업 종료/취침 시각\"],\"proposalItems\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(304L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("안녕 지금부터 프로젝트 더 수정할 건데 계획 한번 짜줄래? "
                        + "오늘은 조금 늦게 자도 괜찮을 것 같아", "k-case-001"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo("오늘 몇 시쯤까지 작업하고, 몇 시쯤 잘 생각이야?");
        assertThat(sink.completed.proposalItems()).isEmpty();
        // OFFER 버튼도 계획 초안도 만들어지지 않는다 — CASE-001의 실패 재현(성급한 OFFER) 방지.
        assertThat(sink.offerAction).isNull();
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void auto_decisionOfferProposal_resolvesToOffer_withServerBuiltOfferAction() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "정보는 충분해 보여.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"proposalItems\":[],\"unavailableWindows\":[],"
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\"}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(302L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("알바는 밤 11시에 끝나고 집에는 12시쯤 도착해.", "k-auto-offer"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        // reply는 모델의 자연어 문장을 그대로 쓴다 — 지금 상황을 짚는 제안("오전 일정 3개가
        // 밀렸네, 다시 잡아볼까?")이 매번 같은 고정 문장으로 뭉개지면 안 되고, 스트리밍 중에
        // 이미 보여준 문장이 완료 시점에 다른 문장으로 바뀌어서도 안 된다.
        assertThat(sink.completed.reply()).isEqualTo("정보는 충분해 보여.");
        // 반면 버튼은 여전히 서버가 만든다 — 모델은 화면 상태나 버튼을 정하지 않는다.
        assertThat(sink.offerAction).isNotNull();
        assertThat(sink.offerAction.type()).isEqualTo("CREATE_PROPOSAL");
        assertThat(sink.offerAction.label()).isEqualTo(DEFAULT_OFFER_LABEL);
        // 버튼은 기간을 들고 있어야 한다 — 화면이 이 날짜를 보여주고, 클릭하면 그대로
        // CREATE_PROPOSAL 요청의 확정 기간이 된다.
        assertThat(sink.offerAction.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(sink.offerAction.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void auto_decisionOfferProposal_fallsBackToFixedReply_whenModelReplyIsEmpty() {
        // 모델이 자연어 문장을 비워 보내면 빈 말풍선을 남기지 않고 서버 문장으로 대체한다.
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"proposalItems\":[],\"unavailableWindows\":[],"
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\"}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(303L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("늦게 일어나서 오전 계획을 다 못했어", "k-auto-offer-empty"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.completed.reply()).isEqualTo(AUTO_OFFER_REPLY);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void auto_decisionProposalReady_isModelContractViolation_downgradesToOffer_discardsProposalData() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // AUTO에서 모델이 계약을 어기고 PROPOSAL_READY를 반환해도 서버는 날짜 검증조차 하지
        // 않고 즉시 OFFER로 강등하며 proposalItems/기간/unavailableWindows를 모두 버린다.
        String raw = "이렇게 만들어봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\","
                + "\"proposalItems\":[{\"title\":\"씻고 정리\",\"description\":null,\"expectedMinutes\":20,"
                + "\"priority\":\"SHOULD\",\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],"
                + "\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(303L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 계획 만들어줘", "k-auto-violation"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.completed.reply()).isEqualTo(AUTO_OFFER_REPLY);
        assertThat(sink.completed.proposalItems()).isEmpty();
        assertThat(sink.proposalReady).isNull();
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<AiResponseType> responseTypeCaptor = ArgumentCaptor.forClass(AiResponseType.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), responseTypeCaptor.capture(), itemsCaptor.capture(),
                any(), any(), any(), any(), any(), any());
        assertThat(responseTypeCaptor.getValue()).isEqualTo(AiResponseType.OFFER);
        assertThat(itemsCaptor.getValue()).isEmpty();
    }

    @Test
    void auto_shortAffirmation_neverCreatesProposal_andNeverLooksUpPriorAssistantMessage() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // "응"만으로는 무슨 판단이든 AUTO는 PROPOSAL을 만들 수 없다 — 설령 모델이 착각해서
        // PROPOSAL_READY를 반환해도 마찬가지다. 이전 세션처럼 "직전 응답이 OFFER였는지"를
        // 조회하는 로직 자체가 이제 존재하지 않는다 — aiMessageMapper가 이 흐름에서 전혀
        // 호출되지 않는 것으로 그 사실을 구조적으로 확인한다.
        String raw = "좋아, 만들어볼게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\","
                + "\"proposalItems\":[{\"title\":\"씻고 정리\",\"description\":null,\"expectedMinutes\":20,"
                + "\"priority\":\"SHOULD\",\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],"
                + "\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(304L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("응", "k-auto-short"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isNotEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.proposalReady).isNull();
        verifyNoInteractions(aiMessageMapper);
    }

    @Test
    void auto_negationSentence_modelDecisionChat_staysChat_noRegexInvolved() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // "계획은 만들지 말고..."라는 문장 자체를 서버가 정규식으로 해석하지 않는다 — 오직
        // 모델의 decision만 본다. 모델이 CHAT이라고 판단했으면 그대로 CHAT이다.
        String raw = "고민을 들어볼게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"proposalItems\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(305L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("계획은 만들지 말고 내 고민만 정리해줘.", "k-auto-negation"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void auto_decisionContractViolation_chatWithClarifyingQuestion_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // decision=CHAT인데 clarifyingQuestion이 채워져 있는 모순 — 조용히 고쳐 쓰지 않고
        // 기존 실패 lifecycle로 처리한다(원칙 7의 일반 규칙, PROPOSAL_READY만 예외).
        String raw = "음.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"clarifyingQuestion\":\"뭔가 이상한 질문\",\"missingInformation\":[],"
                + "\"proposalItems\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 말", "k-auto-contract-violation"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    // ===== decision별 허용 필드 완전 검증 — CHAT/ASK_CLARIFICATION은 기간·unavailableWindows를
    // 만들면 안 되고, 반대로 OFFER_PROPOSAL은 기간이 반드시 있어야 한다(그 기간이 곧 버튼에
    // 실려 사용자에게 보이고 승인된다). 최종 결과에서 버려지더라도 모델 출력 계약 위반 자체를
    // 서버가 잡는다. =====

    @Test
    void auto_chatWithPeriod_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "음.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"periodStartDate\":\"2026-08-10\",\"periodEndDate\":\"2026-08-16\","
                + "\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 말", "k-chat-period"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_chatWithUnavailableWindows_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "음.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"unavailableWindows\":[{\"date\":\"2026-08-10\","
                + "\"startTime\":\"17:00\",\"endTime\":\"23:00\",\"reason\":\"알바\"}],\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 말", "k-chat-windows"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_askClarificationWithPeriod_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "확인할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"언제가 좋아요?\","
                + "\"periodStartDate\":\"2026-08-10\",\"periodEndDate\":\"2026-08-10\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 말", "k-ask-period"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_askClarificationWithUnavailableWindows_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "확인할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"언제가 좋아요?\","
                + "\"unavailableWindows\":[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"17:00\",\"endTime\":\"23:00\","
                + "\"reason\":\"알바\"}],\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 말", "k-ask-windows"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_offerProposalWithoutPeriod_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // OFFER는 "이 기간으로 만들까요?"라는 뜻이라 기간 없이 성립하지 않는다. 기간이 아직
        // 모호하면 모델은 ASK_CLARIFICATION으로 실제 날짜를 보여주며 되물었어야 한다.
        String raw = "좋아 보여.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"periodStartDate\":null,"
                + "\"periodEndDate\":null,\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("정보 다 줬어", "k-offer-period"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_offerProposalWithOnlyStartDate_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 시작만 있고 끝이 없는 기간은 기간이 아니다 — 한쪽만 채운 출력도 계약 위반이다.
        String raw = "좋아 보여.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"periodStartDate\":\"2026-08-10\","
                + "\"periodEndDate\":null,\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("정보 다 줬어", "k-offer-half-period"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auto_offerProposalWithUnavailableWindows_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "좋아 보여.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"periodStartDate\":\"2026-08-05\","
                + "\"periodEndDate\":\"2026-08-05\",\"unavailableWindows\":[{\"dayOfWeek\":\"MONDAY\","
                + "\"startTime\":\"17:00\",\"endTime\":\"23:00\",\"reason\":\"알바\"}],\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("정보 다 줬어", "k-offer-windows"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== 기간 계획: 진입 탭이 아니라 의도가 경로를 정한다 =====

    /*
     * "이번 주 계획 짜줘"는 오늘 탭에서 말해도 기간 계획이다. 모델이 proposalPurpose=PERIOD_PLAN과
     * 기간·강도·대상 프로젝트를 명시하면 서버가 검증해 CREATE_PERIOD_PLAN 버튼을 만든다. 버튼
     * 자체는 여전히 서버가 만든다 — 모델 JSON에는 offerAction 필드가 없다.
     */
    @Test
    void auto_periodPlanOffer_buildsCreatePeriodPlanAction_withValidatedFields() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(courseService.list(USER_ID, com.jungwoo.project.memo.course.domain.CourseStatus.ACTIVE)).thenReturn(List.of(
                com.jungwoo.project.memo.course.dto.CourseResponse.builder().courseId(31L).title("빅데이터분석").build(),
                com.jungwoo.project.memo.course.dto.CourseResponse.builder().courseId(36L).title("자료구조").build()));
        String raw = "이번 주 남은 기간을 집중 강도로 잡아볼까요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalPurpose\":\"PERIOD_PLAN\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-09\","
                + "\"planIntensity\":\"FOCUSED\",\"targetCourseIds\":[36,999],"
                + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("이번 주 계획 짜줘", "k-pp"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.offerAction.type()).isEqualTo("CREATE_PERIOD_PLAN");
        assertThat(sink.offerAction.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(sink.offerAction.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(sink.offerAction.intensity()).isEqualTo(com.jungwoo.project.memo.plan.domain.PlanIntensity.FOCUSED);
        // 남의 것이거나 없는 프로젝트(999)는 서버가 걸러낸다.
        assertThat(sink.offerAction.courseIds()).containsExactly(36L);
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());
    }

    /*
     * 강도를 모르면 기간 계획을 제안하지 않고 한 번 묻는다. 서버가 세 선택지를 붙인다 — 모델이
     * 강도를 추측해 채우는 것도, 계약 위반으로 503을 내는 것도 아니다.
     */
    @Test
    void auto_periodPlanOfferWithoutIntensity_asksOnce_withThreeQuickReplies() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "만들어볼까요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalPurpose\":\"PERIOD_PLAN\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\","
                + "\"planIntensity\":null,\"targetCourseIds\":[],"
                + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 공부 계획 짜줘", "k-pi"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo(AiConversationService.INTENSITY_QUESTION);
        assertThat(sink.completed.quickReplies()).containsExactly("가볍게", "보통", "집중");
        assertThat(sink.offerAction).isNull();
    }

    /** 모델이 스스로 강도를 되물을 때(missingInformation=PLAN_INTENSITY)도 같은 선택지가 붙는다. */
    @Test
    void auto_modelAsksIntensity_getsQuickReplies() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "어느 정도로 채울까요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"proposalPurpose\":\"PERIOD_PLAN\","
                + "\"clarifyingQuestion\":\"이번 기간의 남는 시간 중 어느 정도를 공부로 채울까요? 가볍게 / 보통 / 집중\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-09\","
                + "\"missingInformation\":[\"PLAN_INTENSITY\"],\"proposalItems\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("이번 주 계획 짜줘", "k-ask"), sink);
        awaitTerminal(sink, d);

        // 기간 계획이 강도를 되물을 때는 이미 아는 기간이 붙어 있어도 계약 위반이 아니다.
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.quickReplies()).containsExactly("가볍게", "보통", "집중");
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    /** 목적이 실행 조정이거나 예전 출력처럼 비어 있으면 기존 일반 제안 버튼 그대로다. */
    @Test
    void auto_executionChangeOffer_keepsTheLegacyCreateProposalAction() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "30분 추가해볼까요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalPurpose\":\"EXECUTION_CHANGE\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\","
                + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("자료구조 30분 추가해줘", "k-ec"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.offerAction.type()).isEqualTo("CREATE_PROPOSAL");
        // 실행 조정 OFFER도 기간을 들고 온다 — "오늘 30분 추가"의 기간은 오늘 하루다.
        assertThat(sink.offerAction.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(sink.offerAction.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(sink.offerAction.intensity()).isNull();
    }

    /*
     * 기간 계획 버튼(CREATE_PERIOD_PLAN)은 상담 모델을 부르지 않는다. 계획 화면과 같은
     * PlanDraftService가 한 번 생성하고(generate), 그 결과를 이 턴의 ASSISTANT 메시지와 같은
     * 트랜잭션에 저장한다(completePeriodPlanTurn). 생성 버튼 한 번에 모델 두 번은 없다.
     */
    @Test
    void createPeriodPlan_usesThePlanDraftService_notTheConsultationModel() {
        when(courseService.list(USER_ID, com.jungwoo.project.memo.course.domain.CourseStatus.ACTIVE)).thenReturn(List.of(
                com.jungwoo.project.memo.course.dto.CourseResponse.builder().courseId(31L).title("빅데이터분석").build()));
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt(), any())).thenReturn(List.of(
                AiMessage.builder().messageId(150L).role(MessageRole.USER).content("이번 주 계획 짜줘").build(),
                AiMessage.builder().messageId(151L).role(MessageRole.ASSISTANT).content("강도는요?").build(),
                AiMessage.builder().messageId(152L).role(MessageRole.USER).content("집중으로, 자료구조 위주로").build()));
        PeriodPlanDraftGenerator.Spec spec = new PeriodPlanDraftGenerator.Spec(USER_ID,
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 9),
                com.jungwoo.project.memo.plan.domain.PlanIntensity.FOCUSED, "지시", null, List.of(31L));
        PeriodPlanDraftGenerator.Generated generated = new PeriodPlanDraftGenerator.Generated(
                spec, 600, 510, null, false, "이번 주 집중", null, List.of());
        when(planDraftService.generate(eq(USER_ID), any())).thenReturn(generated);
        com.jungwoo.project.memo.plan.dto.PlanDraftResponse draft = com.jungwoo.project.memo.plan.dto.PlanDraftResponse.builder()
                .proposalId(77L).targetMinutes(510)
                .proposal(AiProposalResponse.builder().proposalId(77L).items(List.of()).build())
                .build();
        when(aiTurnLifecycleService.completePeriodPlanTurn(eq(CONVERSATION_ID), eq(USER_ID), eq(REQUEST_MESSAGE_ID), any(), eq(generated)))
                .thenReturn(new AiTurnLifecycleService.PeriodPlanCompletion(assistantMessage(201L), draft));

        AiMessageRequest request = AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PERIOD_PLAN)
                .sourceMessageId(150L)
                .idempotencyKey("k-cpp")
                .periodPlan(new PeriodPlanRequest(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 9),
                        com.jungwoo.project.memo.plan.domain.PlanIntensity.FOCUSED, List.of(31L, 999L)))
                .build();
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request, sink);
        awaitTerminal(sink, d);

        verifyNoInteractions(aiConsultationClient);
        ArgumentCaptor<com.jungwoo.project.memo.plan.dto.PlanDraftRequest> captor =
                ArgumentCaptor.forClass(com.jungwoo.project.memo.plan.dto.PlanDraftRequest.class);
        verify(planDraftService).generate(eq(USER_ID), captor.capture());
        // 화면이 되돌려 보낸 값 그대로가 아니라 서버가 다시 검증한 값으로 만든다.
        assertThat(captor.getValue().getIntensity()).isEqualTo(com.jungwoo.project.memo.plan.domain.PlanIntensity.FOCUSED);
        assertThat(captor.getValue().getCourseIds()).containsExactly(31L);
        // 대화에서 정한 것이 [사용자 지시]로 넘어간다 — 사용자 발언만, 오래된 것부터.
        assertThat(captor.getValue().getInstruction())
                .contains("이번 주 계획 짜줘").contains("집중으로, 자료구조 위주로").doesNotContain("강도는요?");
        assertThat(sink.periodPlanReady).isSameAs(draft);
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.completed.periodPlanDraft().getProposalId()).isEqualTo(77L);
        assertThat(sink.completed.offerAction()).isNull();
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createPeriodPlan_failureReleasesTheTurn_withTheBusinessErrorCode() {
        when(planDraftService.generate(eq(USER_ID), any()))
                .thenThrow(new com.jungwoo.project.memo.common.exception.ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED));

        AiMessageRequest request = AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PERIOD_PLAN)
                .idempotencyKey("k-cpp-fail")
                .periodPlan(new PeriodPlanRequest(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 9),
                        com.jungwoo.project.memo.plan.domain.PlanIntensity.NORMAL, List.of()))
                .build();
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request, sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_NOT_CONFIGURED);
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
        verifyNoInteractions(aiConsultationClient);
    }

    // ===== 서버가 offerAction/responseType을 직접 만든다는 것을 확인 =====

    @Test
    void serverBuildsOfferAction_evenThoughModelJsonHasNoOfferActionField() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 새 스키마에는 offerAction 필드 자체가 없다 — 모델이 절대 버튼을 구성할 수 없고,
        // OFFER일 때 보여줄 버튼은 항상 서버가 만든다.
        String raw = "좋아 보여.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"periodStartDate\":\"2026-08-05\","
                + "\"periodEndDate\":\"2026-08-05\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(306L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("정보 다 줬어", "k-server-offer"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.offerAction).isNotNull();
        assertThat(sink.offerAction.label()).isEqualTo(DEFAULT_OFFER_LABEL);
    }

    @Test
    void savedResponseType_reflectsServerResolution_notRawModelDecisionName() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // decision=ASK_CLARIFICATION은 AiResponseType에 존재하지 않는 이름이다 — 저장되는
        // responseType은 모델의 decision 문자열을 그대로 쓰는 게 아니라 서버(resolveTurn)가
        // 새로 결정한 AiResponseType.CHAT이어야 한다.
        String raw = "확인할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"언제가 좋아요?\","
                + "\"missingInformation\":[],\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(307L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 좀 도와줘", "k-server-resolve"), sink);
        awaitTerminal(sink, d);

        ArgumentCaptor<AiResponseType> responseTypeCaptor = ArgumentCaptor.forClass(AiResponseType.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), responseTypeCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        assertThat(responseTypeCaptor.getValue()).isEqualTo(AiResponseType.CHAT);
    }

    // ===== CREATE_PROPOSAL 상태 전이/응답 계약 =====

    @Test
    void createProposal_decisionProposalReady_succeeds_andSavesProposal() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "세 조각을 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"clarifyingQuestion\":null,\"missingInformation\":[],"
                + "\"proposalItems\":[{\"title\":\"교재 6장 읽기\",\"description\":null,\"expectedMinutes\":30,"
                + "\"priority\":\"SHOULD\",\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],"
                + "\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(910L).items(List.of()).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(205L), proposalResponse, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp8"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.completed.proposalId()).isEqualTo(910L);
        assertThat(sink.proposalReady).isNotNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService, times(1)).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    @Test
    void createProposal_decisionAskClarification_succeedsAsChat_notAFailure() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 정보 부족은 정상적인 상담 흐름이다 — CREATE_PROPOSAL이라도 503이 아니라 CHAT으로
        // 정상 완료돼야 한다.
        String raw = "하나만 더 확인할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"알바는 몇 시에 끝나나요?\","
                + "\"missingInformation\":[\"알바 종료 시각\"],\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(206L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp9"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isEqualTo("알바는 몇 시에 끝나나요?");
        assertThat(sink.proposalReady).isNull();
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    @Test
    void createProposal_emptyResponse_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse("")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.completed).isNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_noDelimiter_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse("그냥 대답만 하고 끝냄")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp2"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    @Test
    void createProposal_delimiterWithNoJsonAfterIt_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chatResponse("답변\n<<<AI_STRUCTURED>>>\n")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp3"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_invalidJson_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chatResponse("답변\n<<<AI_STRUCTURED>>>\n{이건 유효한 JSON이 아님")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp4"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_decisionChat_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "그냥 대화로만 답할게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp5"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_decisionOfferProposal_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 이미 사용자가 생성을 요청했다 — 다시 OFFER_PROPOSAL로 답하면 계약 위반이다.
        String raw = "먼저 물어볼게.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp6"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_proposalReadyWithEmptyItems_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "초안을 만들어봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp7"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_proposalReadyEchoingThePeriod_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 이 단계의 기간은 사용자가 OFFER 카드에서 승인한 요청값이다. 모델이 같은 값을 다시
        // 적어 보내면(설령 요청과 똑같아도) 계약 위반이다 — 두 값을 비교하는 자리를 만들지
        // 않으려면 애초에 쓰지 못하게 하는 편이 확실하다.
        String raw = "초안을 만들어봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\",\"proposalItems\":["
                + "{\"title\":\"교재 6장 읽기\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp10"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_askClarificationWithProposalItems_violatesContract_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // ASK_CLARIFICATION인데 proposalItems가 있는 모순 — 조용히 고쳐 쓰지 않고 실패시킨다.
        String raw = "확인이 더 필요해.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"알바는 몇 시에 끝나나요?\","
                + "\"missingInformation\":[],\"proposalItems\":["
                + "{\"title\":\"귀가 후 정리\",\"description\":null,\"expectedMinutes\":20,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp11"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== PROPOSAL_READY 필수 필드(reply 빈 값 금지) =====

    @Test
    void createProposal_proposalReadyWithEmptyReply_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // CREATE_PROPOSAL은 실제로 PROPOSAL을 저장하고 그 reply를 assistant 메시지로 남긴다 —
        // 구조화 데이터가 정상이어도 reply가 비어 있으면 빈 문장으로 저장되는 것을 막는다.
        String raw = "<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"교재 읽기\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("cp13"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    @Test
    void auto_proposalReadyWithEmptyReply_stillDowngradesToOffer_notAFailure() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // AUTO+PROPOSAL_READY는 서버 고정 OFFER reply를 쓰므로, 모델의 자연어 reply가 비어
        // 있어도 실패가 아니라 정상적으로 OFFER로 강등된다 — CREATE_PROPOSAL과의 차이점이다.
        String raw = "<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\",\"proposalItems\":["
                + "{\"title\":\"씻고 정리\",\"description\":null,\"expectedMinutes\":20,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(308L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("오늘 계획 만들어줘", "k-auto-empty-reply"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.completed.reply()).isEqualTo(AUTO_OFFER_REPLY);
        assertThat(sink.proposalReady).isNull();
        verify(aiTurnLifecycleService, never()).completeTurnFailure(any(), any(), any());
    }

    // ===== 계획 기간 계약 — 기간은 요청이 정하고, 모델은 그 안에서 내용만 만든다 =====
    //
    // 실제 장애: 2026-09-04(금)에 "이번 주 토일이랑 다음 주까지"(9/5~9/13, 9일)가 모델의
    // planScope=WEEK로 나와 서버의 "WEEK<=7일" 계약에 걸려 턴 전체가 실패했다. 기간 종류를
    // 없애고 기간 결정을 OFFER 단계로 옮긴 뒤에는, 이 단계에서 검증할 것이 "요청 기간이
    // 유효한가"와 "모델이 만든 후보가 그 안에 있는가" 둘뿐이다.

    @Test
    void createProposal_targetDateFollowsTheRequestedPeriod_notToday() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 사용자가 OFFER 카드에서 "내일"을 승인했다(2026-08-06). 오늘은 8/5지만 서버가
        // LocalDate.now()로 되돌리지 않는다 — 기간의 주인은 요청이다.
        String raw = "내일 일정을 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"아침 스트레칭\",\"description\":null,\"expectedMinutes\":20,\"priority\":\"SHOULD\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(931L).items(List.of()).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(217L), proposalResponse, List.of(), List.of()));

        LocalDate tomorrow = LocalDate.of(2026, 8, 6);
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-tomorrow-1", tomorrow, tomorrow), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);

        ArgumentCaptor<LocalDate> targetDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), targetDateCaptor.capture(), any(), any(), any(), any());
        assertThat(targetDateCaptor.getValue()).isEqualTo(tomorrow);
    }

    @Test
    void createProposal_confirmedPeriodIsGivenToTheModel_asAnInstruction() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"복습\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(240L),
                        AiProposalResponse.builder().proposalId(940L).items(List.of()).build(), List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-confirmed-1", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 13)), sink);
        awaitTerminal(sink, d);

        // 모델은 기간을 해석하지 않는다 — 확정된 날짜를 지시로 받는다.
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), any(), any());
        assertThat(userPrompt.getValue()).contains("[확정된 계획 기간]");
        assertThat(userPrompt.getValue()).contains("계획 시작일: 2026-09-05");
        assertThat(userPrompt.getValue()).contains("계획 종료일: 2026-09-13");
        assertThat(userPrompt.getValue()).contains("너는 이 기간을 다시 판단하지 않는다");
    }

    /*
     * 실제 장애의 회귀 테스트. 2026-09-04(금)에 "이번 주 토일이랑 다음 주까지"를 승인해
     * 9/5~9/13(9일)로 초안을 만드는 요청이다. 예전에는 이 9일이 planScope=WEEK의 7일 계약에
     * 걸려 통째로 실패했다. 이제는 기간 종류라는 개념이 없으므로 그냥 통과해야 한다.
     */
    @Test
    void createProposal_nineDayPeriodAcrossCalendarWeeks_succeeds() {
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.UTC));
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "토요일부터 다음 주 일요일까지 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"missingInformation\":[],\"adjustments\":[],"
                + "\"unavailableWindows\":[],\"proposalItems\":["
                + "{\"title\":\"자료구조 복습\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null,"
                + "\"earliestStartDate\":\"2026-09-05\",\"deadlineDate\":\"2026-09-13\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(935L).items(List.of()).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(230L), proposalResponse, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-range-1", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 13)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.proposalReady).isNotNull();

        // 시작을 오늘(9/4)로 당기지 않는다 — 사용자가 승인한 것은 "토일부터"다.
        ArgumentCaptor<LocalDate> targetDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), targetDateCaptor.capture(), any(), any(), any(), any());
        assertThat(targetDateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void createProposal_period31DaysInclusive_succeeds() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 시작·종료를 포함해 31일 — 기간형 계획(MAX_PLAN_DAYS)과 같은 상한의 경계값이다.
        String raw = "한 달치로 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"자료구조 복습\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(937L).items(List.of()).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(232L), proposalResponse, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-range-4", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 4)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.proposalReady).isNotNull();
    }

    @Test
    void createProposal_period32DaysInclusive_isRejectedBeforeCallingTheModel() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-range-5", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 5)), sink);
        awaitTerminal(sink, d);

        // 32일은 한 번에 만드는 계획의 상한을 넘는다. 모델을 부르기 전에 막는다 — 계약을
        // 어긴 것은 모델이 아니라 요청이므로 AI_GENERATION_FAILED가 아니라 잘못된 입력이다.
        assertThat(sink.errorCode).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThat(sink.proposalReady).isNull();
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    @Test
    void createProposal_reversedPeriod_isRejectedBeforeCallingTheModel() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-range-6", LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 5)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    void createProposal_periodEntirelyInThePast_isRejectedBeforeCallingTheModel() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 고정 시계는 2026-08-05다. 아래 기간은 그보다 앞서 끝나므로 배치할 구간이 없다.
        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-past-1", LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_periodThatStartedInThePastButStillCoversToday_isAllowed() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // "이번 주" — 시작(8/3)은 지났지만 오늘(8/5)을 포함한다. 요청 기간을 그대로 둔다.
        // 배치를 오늘 이후로 제한하는 것은 배치 가능 구간의 일이지 요청 기간의 일이 아니다.
        String raw = "이번 주 계획이에요.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"missingInformation\":[],\"adjustments\":[],"
                + "\"unavailableWindows\":[],\"proposalItems\":["
                + "{\"title\":\"자료구조 복습\",\"description\":null,\"expectedMinutes\":60,"
                + "\"priority\":\"SHOULD\",\"placementType\":\"UNSCHEDULED\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(410L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-past-2", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        ArgumentCaptor<LocalDate> targetDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), targetDateCaptor.capture(), any(), any(), any(), any());
        assertThat(targetDateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    // --- 후보/조정 날짜는 확정 기간 밖으로 나갈 수 없다(양방향) ---

    @Test
    void createProposal_itemDeadlineAfterPeriodEnd_violatesContract_notClamped() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 확정 기간은 오늘 하루인데 UNSCHEDULED 항목의 deadlineDate가 일주일 뒤다 —
        // 오늘로 조용히 클램프하지 않고 계약 위반으로 실패시킨다.
        String raw = "오늘 안에서만 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"이번 주 공부\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null,"
                + "\"earliestStartDate\":\"2026-08-05\",\"deadlineDate\":\"2026-08-12\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("k-scope-1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createProposal_earliestStartDateAfterPeriodEndDate_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // earliestStartDate(8/20)가 확정 기간의 끝(8/11)보다 이후 — 예전에는 시작보다 이른지만
        // 봤기 때문에 이 방향은 통과할 수 있었다.
        String raw = "이번 주 계획을 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"주간 정리\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null,"
                + "\"earliestStartDate\":\"2026-08-20\",\"deadlineDate\":null}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-week-3", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 11)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void createProposal_deadlineDateBeforePeriodStartDate_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // deadlineDate(8/1)가 확정 기간의 시작(8/5)보다 이전 — 반대 방향도 잡아야 한다.
        String raw = "이번 주 계획을 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"주간 정리\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null,"
                + "\"earliestStartDate\":null,\"deadlineDate\":\"2026-08-01\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-week-4", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 11)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void createProposal_earliestStartDateAfterDeadlineDate_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // earliestStartDate(8/10)가 deadlineDate(8/7)보다 이후 — 둘 다 개별적으로는 확정
        // 기간(8/5~8/11) 안이라 범위 검사만으로는 못 잡는다.
        String raw = "이번 주 계획을 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"주간 정리\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"UNSCHEDULED\",\"startTime\":null,\"endTime\":null,"
                + "\"earliestStartDate\":\"2026-08-10\",\"deadlineDate\":\"2026-08-07\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-week-5", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 11)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void createProposal_fixedEndAtAfterPeriodEndDate_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // fixedEndAt(8/12) 날짜가 확정 기간의 끝(8/11)보다 이후 — fixedStartAt은 범위 안(8/11)
        // 이라 fixedStartAt만 보면 통과할 뻔한 케이스다.
        String raw = "이번 주 계획을 이렇게 잡아봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"자정 넘는 일정\",\"description\":null,\"expectedMinutes\":60,\"priority\":\"SHOULD\","
                + "\"placementType\":\"TIME_FIXED\",\"startTime\":null,\"endTime\":null,"
                + "\"fixedStartAt\":\"2026-08-11T23:30\",\"fixedEndAt\":\"2026-08-12T00:30\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-week-6", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 11)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void createProposal_moveAdjustmentOutsideThePeriod_fails() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // MOVE 목적지(9/20)가 확정 기간(9/5~9/13) 밖 — "이 기간을 다시 잡아줘"라고 했는데
        // 다음 달로 옮기는 제안이 조용히 통과하면 안 된다.
        String raw = "하나는 뒤로 옮겨봤어.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"missingInformation\":[],\"unavailableWindows\":[],"
                + "\"proposalItems\":[],\"adjustments\":[{\"executionItemId\":77,\"operation\":\"MOVE\","
                + "\"toDate\":\"2026-09-20\",\"reason\":\"이번 기간에는 무리\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-range-8", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 13)), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        assertThat(sink.proposalReady).isNull();
    }

    // ===== 토큰/오류/타임아웃(decision과 무관한 일반 실패 경로) =====

    @Test
    void tokenLimitReached_partialResponse_fails_evenForPlainAutoMessage() {
        // CREATE_PROPOSAL 전용이 아니라 일반적인 판정임을 확인한다: AUTO 요청이라도 토큰
        // 상한(LENGTH)에서 끊겨 구조화 데이터가 전혀 없이 답변만 일부 남은 경우는 실패로 본다.
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chatResponse("부분적으로만 답변하다가 끊김", "LENGTH", 1772, 2400)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("아무 질문", "k-len-1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
    }

    @Test
    void normalFinish_withOutputTokensCoincidentallyEqualToCap_doesNotFail() {
        // finishReason=STOP(정상 종료)인데 우연히 outputTokens가 상한과 같은 값이어도 실패로
        // 몰지 않는다 — 보조 판정(outputTokens>=cap)은 finishReason을 못 얻었을 때만 쓴다.
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "정상적으로 잘 끝난 답변.\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chatResponse(raw, "STOP", 1772, 6000)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(206L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("안녕", "k-len-2"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
    }

    @Test
    void streamAndComplete_openAiError_marksFailed_releasesLock_noRetry() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("429 insufficient_quota")));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 짜줘", "k5"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
        assertThat(sink.completed).isNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
        verify(aiProposalService, never()).createFromItems(any(), any(), any(), any(), any(), any());
    }

    @Test
    void streamAndComplete_timeout_marksFailed_releasesLock_noRetry() {
        // 테스트가 실제로 90초를 기다리지 않도록 타임아웃을 1초로 줄인다.
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // 절대 완료되지 않는 스트림 — .timeout()이 강제로 끊어야 한다.
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.never());

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("계획 짜줘", "k6"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNotNull();
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    // ===== idempotency 재생(AiResponseType은 DB 저장값을 그대로 재생 — 이번 리팩터링과 무관) =====

    @Test
    void replayStoredTurn_repliesChat_doesNotCallAiAtAll() {
        AiMessage requestMessage = AiMessage.builder()
                .messageId(300L).userId(USER_ID).conversationId(CONVERSATION_ID).build();
        AiMessage storedAssistantReply = AiMessage.builder()
                .messageId(301L).userId(USER_ID).role(MessageRole.ASSISTANT)
                .content("이미 답변했던 내용").responseType(AiResponseType.CHAT)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findByReplyToMessageIdAndUserId(300L, USER_ID)).thenReturn(storedAssistantReply);

        RecordingSink sink = new RecordingSink();
        AiConversation conversation = conversation();
        AiTurnLifecycleService.PreparedTurn replay = replayPreparedTurn(conversation, requestMessage);

        service.replayStoredTurn(replay, sink);

        assertThat(sink.completed.reply()).isEqualTo("이미 답변했던 내용");
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayStoredTurn_repliesOffer_rebuildsOfferAction() {
        AiMessage requestMessage = AiMessage.builder()
                .messageId(310L).userId(USER_ID).conversationId(CONVERSATION_ID).build();
        AiMessage storedAssistantReply = AiMessage.builder()
                .messageId(311L).userId(USER_ID).role(MessageRole.ASSISTANT)
                .content(AUTO_OFFER_REPLY).responseType(AiResponseType.OFFER)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findByReplyToMessageIdAndUserId(310L, USER_ID)).thenReturn(storedAssistantReply);

        RecordingSink sink = new RecordingSink();
        AiTurnLifecycleService.PreparedTurn replay = replayPreparedTurn(conversation(), requestMessage);

        service.replayStoredTurn(replay, sink);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.offerAction).isNotNull();
        assertThat(sink.offerAction.label()).isEqualTo(DEFAULT_OFFER_LABEL);
        // 저장된 메시지에는 그때의 기간이 없다(컬럼을 늘리지 않았다). 버튼을 지우는 대신
        // 가장 좁은 범위인 오늘 하루로 되돌리고, 화면이 그 날짜를 그대로 보여준다.
        assertThat(sink.offerAction.periodStartDate()).isEqualTo(TODAY);
        assertThat(sink.offerAction.periodEndDate()).isEqualTo(TODAY);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    void replayStoredTurn_repliesProposal_reloadsSavedProposal() {
        AiMessage requestMessage = AiMessage.builder()
                .messageId(320L).userId(USER_ID).conversationId(CONVERSATION_ID).build();
        AiMessage storedAssistantReply = AiMessage.builder()
                .messageId(321L).userId(USER_ID).role(MessageRole.ASSISTANT)
                .content("초안을 만들었어.").responseType(AiResponseType.PROPOSAL)
                .status(MessageStatus.COMPLETED).build();
        when(aiMessageMapper.findByReplyToMessageIdAndUserId(320L, USER_ID)).thenReturn(storedAssistantReply);
        AiProposalResponse proposalResponse = AiProposalResponse.builder().proposalId(940L).items(List.of()).build();
        when(aiProposalService.findBySourceMessageId(321L, USER_ID)).thenReturn(proposalResponse);

        RecordingSink sink = new RecordingSink();
        AiTurnLifecycleService.PreparedTurn replay = replayPreparedTurn(conversation(), requestMessage);

        service.replayStoredTurn(replay, sink);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(sink.completed.proposalId()).isEqualTo(940L);
        assertThat(sink.proposalReady).isNotNull();
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
    }

    // ===== 대화 조회/목록 =====

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

    // ===== 대화 삭제 =====

    @Test
    void deleteConversation_marksArchived_withoutTouchingMessagesOrProposals() {
        // 화면에서는 "삭제"지만 내부는 soft delete다 — 메시지·제안·이미 반영된 장기 컨텍스트가
        // 이 대화를 참조하고 있어서 행을 지우면 그 관계가 끊어진다.
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(AiConversation.builder()
                        .conversationId(CONVERSATION_ID).userId(USER_ID)
                        .status(ConversationStatus.ACTIVE).build());

        service.deleteConversation(CONVERSATION_ID, USER_ID);

        verify(aiConversationMapper).updateStatus(CONVERSATION_ID, USER_ID, "ARCHIVED");
        verify(aiMessageMapper, never()).findByConversationIdAndUserId(any(), any());
        verifyNoInteractions(aiProposalService, contextChangeSuggestionService);
    }

    @Test
    void deleteConversation_throwsNotFound_whenConversationNotOwned() {
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteConversation(CONVERSATION_ID, USER_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(aiConversationMapper, never()).updateStatus(any(), any(), any());
    }

    @Test
    void deleteConversation_isIdempotent_whenAlreadyDeleted() {
        // 두 화면에서 같은 대화를 지우는 흔한 경우를 오류로 만들지 않는다.
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(AiConversation.builder()
                        .conversationId(CONVERSATION_ID).userId(USER_ID)
                        .status(ConversationStatus.ARCHIVED).build());

        service.deleteConversation(CONVERSATION_ID, USER_ID);

        verify(aiConversationMapper).updateStatus(CONVERSATION_ID, USER_ID, "ARCHIVED");
    }

    @Test
    void listConversations_queriesByUserId_andCleansUpTitles() {
        AiConversationResponse raw = AiConversationResponse.builder()
                .conversationId(CONVERSATION_ID)
                .title("  프로젝트 때문에\n\n   좀    막막해요   정말로 어떻게 해야 할지 모르겠어요  ")
                .lastMessageAt(LocalDateTime.of(2026, 8, 5, 10, 0))
                .pendingProposalCount(2)
                .build();
        when(aiConversationMapper.findSummariesByUserId(USER_ID, null, true, null)).thenReturn(new ArrayList<>(List.of(raw)));

        List<AiConversationResponse> result = service.listConversations(USER_ID, null, true, null);

        verify(aiConversationMapper).findSummariesByUserId(USER_ID, null, true, null);
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
        when(aiConversationMapper.findSummariesByUserId(USER_ID, null, true, null)).thenReturn(new ArrayList<>(List.of(raw)));

        List<AiConversationResponse> result = service.listConversations(USER_ID, null, true, null);

        assertThat(result.get(0).getTitle()).isEqualTo("안녕");
    }

    /*
     * 오늘·일정·전체 탭은 전부 courseId가 없다. scope 없이 조회하면 일정 탭이 오늘 탭에서 만든
     * 대화를 최근 것이라는 이유로 다시 열어, 화면은 일정인데 저장된 대화는 TODAY인 상태가 됐다.
     * 화면이 보낸 scope를 그대로 조회 조건으로 넘긴다. 예전 클라이언트(scope 생략)는 그대로다.
     */
    @Test
    void listConversations_filtersByScope_whenTheScreenSendsOne() {
        when(aiConversationMapper.findSummariesByUserId(USER_ID, null, true, "EXECUTION"))
                .thenReturn(new ArrayList<>());

        service.listConversations(USER_ID, null, true, AiProposalTargetScope.EXECUTION);

        verify(aiConversationMapper).findSummariesByUserId(USER_ID, null, true, "EXECUTION");
    }

    // ===== 일정 후보(scheduleSuggestions) sidecar =====
    //
    // 여기서 고정하는 것은 서버가 후보를 어디로 보내느냐다. "이 말이 반복인가 한 번인가"는
    // 모델의 판단이라 여기서 증명할 수 없다 — 그 사례는
    // docs/product/09-ai-consultation-regression-cases.md에 자연어로 남긴다.

    @Test
    void auto_chat_withCommitmentSuggestion_passesThrough_andEmitsScheduleSuggestionsReady() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = """
                금요일 저녁으로 잡아둘게요.
                <<<AI_STRUCTURED>>>
                {"decision":"CHAT","proposalItems":[],
                 "scheduleSuggestions":[{"kind":"COMMITMENT","payload":{
                   "title":"친구 약속","startAt":"2026-09-04T19:00","endAt":"2026-09-04T21:00",
                   "locationText":"홍대"}}]}""";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        ScheduleSuggestionResponse suggestionResponse =
                ScheduleSuggestionResponse.builder().suggestionId(700L).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(402L), null, List.of(), List.of(suggestionResponse)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(
                preparedTurn(), request("금요일 7시부터 9시까지 홍대에서 친구 만나", "k-sch-1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.scheduleSuggestionsReady).containsExactly(suggestionResponse);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleSuggestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).kind()).isEqualTo(ScheduleSuggestionKind.COMMITMENT);
        // 실행 조각 제안으로 새지 않는다 — 약속은 수행 대상이 아니다.
        assertThat(sink.completed.proposalItems()).isEmpty();
        assertThat(sink.proposalReady).isNull();
    }

    @Test
    void auto_chat_withRoutineSuggestion_passesKindThrough() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = """
                매주 목요일로 등록할까요?
                <<<AI_STRUCTURED>>>
                {"decision":"CHAT","proposalItems":[],
                 "scheduleSuggestions":[{"kind":"ROUTINE","payload":{
                   "title":"알바","daysOfWeek":["THURSDAY"],"startTime":"18:00","endTime":"23:00",
                   "effectiveFrom":"2026-09-01"}}]}""";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(403L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(
                preparedTurn(), request("이번 학기 매주 목요일 6시부터 11시까지 알바해", "k-sch-2"), sink);
        awaitTerminal(sink, d);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleSuggestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue().get(0).kind()).isEqualTo(ScheduleSuggestionKind.ROUTINE);
    }

    @Test
    void createProposal_withScheduleSuggestions_isNotBlocked() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        // "금요일 7~9시 친구 만나니까 나머지로 공부 계획 짜줘" — 후보와 계획이 한 턴에 나온다.
        String raw = """
                그 시간 빼고 잡았어요.
                <<<AI_STRUCTURED>>>
                {"decision":"PROPOSAL_READY",
                 "missingInformation":[],"adjustments":[],
                 "proposalItems":[{"title":"자료구조 복습","expectedMinutes":60,
                   "priority":"SHOULD","placementType":"DATE_ONLY"}],
                 "unavailableWindows":[{"date":"2026-09-04","startTime":"19:00",
                   "endTime":"21:00","reason":"친구 약속"}],
                 "scheduleSuggestions":[{"kind":"COMMITMENT","payload":{
                   "title":"친구 약속","startAt":"2026-09-04T19:00","endAt":"2026-09-04T21:00"}}]}""";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(404L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                createProposalRequest("k-sch-3", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)), sink);
        awaitTerminal(sink, d);

        // contextChanges와 달리 CREATE_PROPOSAL에서 막지 않는다. 막으면 "약속 빼고 계획 짜줘"가
        // 통째로 실패한다.
        assertThat(sink.errorCode).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleSuggestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).hasSize(1);

        // 같은 턴의 계획 계산에는 그 시간이 임시 unavailable로 반영된다(영구 저장은 승인 후).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnavailableWindowSpec>> windows = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), windows.capture(), any(), any(), any());
        assertThat(windows.getValue()).hasSize(1);
    }

    // ===== Context 변경 후보(contextChanges) sidecar =====

    @Test
    void auto_chat_withContextChanges_passesThrough_andEmitsContextSuggestionsReady() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "알겠어요.\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[],"
                + "\"contextChanges\":[{\"operation\":\"SUPERSEDE\",\"targetContextId\":13,"
                + "\"content\":\"현재 알바에서 집까지 약 20분 걸린다.\",\"reason\":\"사용자가 새 이동시간을 알려줌\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        ContextSuggestionResponse suggestionResponse = ContextSuggestionResponse.builder().suggestionId(900L).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(401L), null, List.of(suggestionResponse), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("이사해서 이동시간 짧아졌어", "k-ctx-1"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.contextSuggestionsReady).containsExactly(suggestionResponse);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContextChangeSuggestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture(), any(), any());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).operation()).isEqualTo(ContextChangeOperation.SUPERSEDE);
        assertThat(captor.getValue().get(0).targetContextId()).isEqualTo(13L);
    }

    @Test
    void auto_offer_withContextChanges_stillEmitsContextSuggestionsReady() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "초안을 만들어볼까요?\n<<<AI_STRUCTURED>>>\n{\"decision\":\"OFFER_PROPOSAL\",\"proposalItems\":[],"
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-05\","
                + "\"contextChanges\":[{\"operation\":\"ADD\",\"targetContextId\":null,"
                + "\"content\":\"늦게 퇴근한 다음 날에는 가벼운 계획을 선호한다.\",\"reason\":\"사용자가 말함\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        ContextSuggestionResponse suggestionResponse = ContextSuggestionResponse.builder().suggestionId(901L).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(402L), null, List.of(suggestionResponse), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("피곤한 다음날은 가볍게 하고 싶어", "k-ctx-2"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        assertThat(sink.contextSuggestionsReady).containsExactly(suggestionResponse);
    }

    @Test
    void auto_askClarification_withContextChanges_stillEmitsContextSuggestionsReady() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "몇 시에 끝나나요?\n<<<AI_STRUCTURED>>>\n{\"decision\":\"ASK_CLARIFICATION\","
                + "\"clarifyingQuestion\":\"알바는 몇 시에 끝나나요?\",\"proposalItems\":[],"
                + "\"contextChanges\":[{\"operation\":\"MARK_STALE\",\"targetContextId\":20,"
                + "\"content\":null,\"reason\":\"전제가 바뀐 것 같음\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        ContextSuggestionResponse suggestionResponse = ContextSuggestionResponse.builder().suggestionId(902L).build();
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(403L), null, List.of(suggestionResponse), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("이제 학교를 안 다녀", "k-ctx-3"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.contextSuggestionsReady).containsExactly(suggestionResponse);
    }

    @Test
    void createProposal_withContextChanges_failsTurn_contextChangesForcedEmptyOnly() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "초안이에요.\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"PROPOSAL_READY\",\"proposalItems\":["
                + "{\"title\":\"할 일\",\"description\":null,\"expectedMinutes\":30,\"priority\":\"MUST\","
                + "\"placementType\":\"DATE_ONLY\",\"startTime\":null,\"endTime\":null}],"
                + "\"contextChanges\":[{\"operation\":\"ADD\",\"targetContextId\":null,"
                + "\"content\":\"새 정보\",\"reason\":\"reason\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), createProposalRequest("k-ctx-4"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNotNull();
        verify(aiTurnLifecycleService, never()).completeTurnSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aiGeneratedContextChanges_doesNotAutoModifyUserContexts_onlySavedAsProposed() {
        // AiTurnLifecycleService는 이 테스트에서 목이므로, contextChanges 저장/검증은
        // ContextChangeSuggestionServiceTest가 담당한다. 여기서는 AiConversationService가
        // user_contexts나 UserContextMapper/ContextChangeSuggestionService를 직접 건드리지
        // 않고, 항상 aiTurnLifecycleService.completeTurnSuccess에만 위임한다는 것만 확인한다.
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "알겠어요.\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"proposalItems\":[],"
                + "\"contextChanges\":[{\"operation\":\"ADD\",\"targetContextId\":null,"
                + "\"content\":\"새 정보\",\"reason\":\"reason\"}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(
                        assistantMessage(404L), null, List.of(ContextSuggestionResponse.builder()
                                .suggestionId(903L).status(com.jungwoo.project.memo.ai.domain.ContextSuggestionStatus.PROPOSED)
                                .build()), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("새로운 사실이야", "k-ctx-5"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.contextSuggestionsReady).hasSize(1);
        assertThat(sink.contextSuggestionsReady.get(0).getStatus())
                .isEqualTo(com.jungwoo.project.memo.ai.domain.ContextSuggestionStatus.PROPOSED);
        verifyNoInteractions(contextChangeSuggestionService);
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

    /** finishReason/토큰 사용량을 함께 실어야 하는 테스트(토큰 상한 종료 판정 등)용. */
    private ChatResponse chatResponse(String text, String finishReason, Integer promptTokens, Integer completionTokens) {
        Generation generation = finishReason != null
                ? new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finishReason).build())
                : new Generation(new AssistantMessage(text));
        if (promptTokens == null && completionTokens == null) {
            return new ChatResponse(List.of(generation));
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    /** 확정 기간을 따로 말하지 않는 테스트는 오늘 하루(2026-08-05)를 승인한 것으로 본다. */
    private AiMessageRequest createProposalRequest(String idempotencyKey) {
        return createProposalRequest(idempotencyKey, TODAY, TODAY);
    }

    /**
     * OFFER 카드에서 사용자가 날짜를 보고 누른 상태를 흉내 낸다 — CREATE_PROPOSAL의 기간은
     * 모델 출력이 아니라 이 요청이 들고 오는 값이다.
     */
    private AiMessageRequest createProposalRequest(String idempotencyKey, LocalDate start, LocalDate end) {
        return AiMessageRequest.builder()
                .message(null)
                .requestedAction(RequestedAction.CREATE_PROPOSAL)
                .sourceMessageId(199L)
                .periodStartDate(start)
                .periodEndDate(end)
                .idempotencyKey(idempotencyKey)
                .build();
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

    // ===== 상담 모델 분리(ai.consultation.model) =====

    /**
     * 상담 경로만 mid 티어 모델을 쓴다. 전용 프로퍼티가 비어 있으면 전역 모델로 내려간다 —
     * ai.schedule-import.model과 같은 "비면 전역 fallback" 패턴이다.
     */
    @Test
    void resolveConsultationModel_prefersDedicatedProperty_andFallsBackToGlobal() {
        ReflectionTestUtils.setField(service, "modelName", "gpt-5.6-luna");

        ReflectionTestUtils.setField(service, "consultationModel", "gpt-5.6-terra");
        assertThat(service.resolveConsultationModel()).isEqualTo("gpt-5.6-terra");

        ReflectionTestUtils.setField(service, "consultationModel", "");
        assertThat(service.resolveConsultationModel()).isEqualTo("gpt-5.6-luna");

        ReflectionTestUtils.setField(service, "consultationModel", null);
        assertThat(service.resolveConsultationModel()).isEqualTo("gpt-5.6-luna");
    }

    /** 실제로 부른 모델이 스트리밍 호출과 ai_usage_logs 기록 양쪽에 같은 값으로 들어가야 한다. */
    @Test
    void streamAndComplete_usesConsultationModel_forCallAndUsageLog() {
        ReflectionTestUtils.setField(service, "modelName", "gpt-5.6-luna");
        ReflectionTestUtils.setField(service, "consultationModel", "gpt-5.6-terra");
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        String raw = "안녕!\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"CHAT\",\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), eq("gpt-5.6-terra")))
                .thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("안녕", "k-model"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), eq("gpt-5.6-terra"));
        verify(aiUsageLimitService).record(eq(USER_ID), eq(CONVERSATION_ID), eq(REQUEST_MESSAGE_ID),
                eq("gpt-5.6-terra"), any(), any(), any(),
                eq(com.jungwoo.project.memo.ai.domain.UsageResultStatus.SUCCESS), any());
    }

    // ===== 진행 중 요청(draft) =====

    private static final String DRAFT_STRUCTURED_TAIL =
            ",\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[],\"contextChanges\":[],\"scheduleSuggestions\":[]";

    private com.jungwoo.project.memo.ai.draft.AiConversationDraft openRoutineDraftEntity() {
        return com.jungwoo.project.memo.ai.draft.AiConversationDraft.builder()
                .draftId(31L).userId(USER_ID).conversationId(CONVERSATION_ID).draftGroupId(31L)
                .draftType(com.jungwoo.project.memo.ai.draft.DraftType.CREATE_ROUTINE)
                .label("수업 전 이동 루틴")
                .status(com.jungwoo.project.memo.ai.draft.DraftStatus.OPEN)
                .fields("{\"durationMinutes\":{\"value\":60,\"source\":\"USER\",\"reason\":null,\"confirmationRequired\":false},"
                        + "\"anchor\":{\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"INFERRED\",\"reason\":null,\"confirmationRequired\":true}}")
                .missingRequired("[]")
                .askCount(0)
                .createdAt(LocalDateTime.of(2026, 8, 5, 14, 0))
                .build();
    }

    /**
     * B-8 #13: CREATE_ROUTINE draft가 OPEN인 턴에서 모델이 PERIOD_PLAN OFFER를 강도 없이 내도 기존
     * "강도 되묻기"(INTENSITY_QUESTION)가 발동하지 않는다 — 그 재질문은 CREATE_PERIOD_PLAN draft가
     * 있을 때만 동작한다. draft를 겨냥한 routing이 없으므로 일반 CHAT으로 끝난다.
     */
    @Test
    void openRoutineDraft_suppressesPeriodPlanIntensityRequestion() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        when(aiConversationDraftMapper.findOpenByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(List.of(openRoutineDraftEntity()));
        when(draftFactsService.collect(eq(USER_ID), any()))
                .thenReturn(com.jungwoo.project.memo.ai.draft.DraftFacts.empty(TODAY));
        String raw = "이번 주 계획을 만들어볼까요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalPurpose\":\"PERIOD_PLAN\","
                + "\"periodStartDate\":\"2026-08-05\",\"periodEndDate\":\"2026-08-09\",\"planIntensity\":null,"
                + "\"routing\":{\"targets\":[],\"create\":[]},\"draftOps\":[],\"actionHint\":\"CHAT\",\"userTriggered\":false"
                + DRAFT_STRUCTURED_TAIL + "}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(), request("그냥 루틴만 만들어줘", "k-draft-13"), sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
        assertThat(sink.completed.reply()).isNotEqualTo(AiConversationService.INTENSITY_QUESTION);
        assertThat(sink.completed.quickReplies()).isEmpty();
        // 프롬프트에는 열려 있는 작업 블록이 실려 나간다.
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPromptCaptor.capture(), any(), any());
        assertThat(userPromptCaptor.getValue()).contains("[열려 있는 작업]").contains("#31 CREATE_ROUTINE");
    }

    /**
     * B-8 #1을 서비스 경계에서: 첫 문장에 모델이 draft 둘을 create하면 서버가 PROPOSE(SCHEDULE만)로 판정하고,
     * 모델 reply를 버린 서버 문구로 ASSISTANT를 저장하며, draft 저장·승격은 completeTurnSuccess의
     * 트랜잭션(DraftTurnCommit)으로 넘긴다. 모델의 scheduleSuggestions는 같은 요청의 중복이라 버린다.
     */
    @Test
    void firstUtterance_draftTurn_proposesViaServerReply_andCommitsDrafts() {
        when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("");
        stubDraftFacts(new com.jungwoo.project.memo.ai.draft.DraftFacts(
                TODAY,
                java.util.Map.of(java.time.DayOfWeek.WEDNESDAY, java.time.LocalTime.of(10, 0)),
                java.util.Optional.of(LocalDate.of(2026, 12, 11)),
                com.jungwoo.project.memo.ai.draft.DraftFacts.WorkLookup.OK,
                List.of(new com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift(
                        1L, "근무", LocalDateTime.of(2026, 8, 5, 18, 0), LocalDateTime.of(2026, 8, 5, 23, 0))),
                List.of(), java.util.Map.of(),
                TODAY, TODAY.plusDays(14)));
        String raw = "수업 전마다요, 아니면 첫 수업 전만요?\n<<<AI_STRUCTURED>>>\n"
                + "{\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"수업 전마다요, 아니면 첫 수업 전만요?\","
                + "\"routing\":{\"targets\":[],\"create\":["
                + "{\"draftType\":\"CREATE_ROUTINE\",\"label\":\"수업 전 이동 루틴\",\"sameGroupAs\":null},"
                + "{\"draftType\":\"CREATE_SCHEDULE\",\"label\":\"근무 전 이동 블록\",\"sameGroupAs\":\"new-0\"}]},"
                + "\"draftOps\":["
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"INFERRED\",\"confirmationRequired\":false},"
                + "{\"draftId\":\"new-1\",\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                + "{\"draftId\":\"new-1\",\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"BEFORE_EACH_WORK_SHIFT\",\"source\":\"INFERRED\"}],"
                + "\"actionHint\":\"ASK\",\"userTriggered\":false,"
                + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[],\"contextChanges\":[],"
                + "\"scheduleSuggestions\":[{\"kind\":\"COMMITMENT\",\"payload\":{\"title\":\"이동\",\"startAt\":\"2026-08-05T17:00\",\"endAt\":\"2026-08-05T18:00\"}}]}";
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chatResponse(raw)));
        when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistantMessage(201L), null, List.of(), List.of()));

        RecordingSink sink = new RecordingSink();
        Disposable d = service.streamAndComplete(preparedTurn(),
                request("수업 시간 전에 1시간 이동시간 블록 반복일정으로 만들어주고 근무 전에는 1시간 이동 블록 1회성으로 만들어줘", "k-draft-1"),
                sink);
        awaitTerminal(sink, d);

        assertThat(sink.errorCode).isNull();
        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<ScheduleSuggestion>> modelSuggestions = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<AiTurnLifecycleService.DraftTurnCommit> commitCaptor =
                ArgumentCaptor.forClass(AiTurnLifecycleService.DraftTurnCommit.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(eq(CONVERSATION_ID), eq(USER_ID), eq(REQUEST_MESSAGE_ID),
                replyCaptor.capture(), eq(AiResponseType.CHAT), any(), any(), any(), any(), any(),
                modelSuggestions.capture(), commitCaptor.capture());
        // 서버 문구: SCHEDULE 후보 1건 + 남은 ROUTINE 확인 질문 한 줄(mixed)
        assertThat(replyCaptor.getValue()).contains("근무 전 이동 블록 후보 1건").endsWith("첫 수업 전만요?");
        assertThat(modelSuggestions.getValue()).isEmpty();
        var outcome = commitCaptor.getValue().outcome();
        assertThat(outcome.action()).isEqualTo(com.jungwoo.project.memo.ai.draft.DraftTurnResolver.Action.PROPOSE);
        assertThat(outcome.drafts()).hasSize(2);
        assertThat(outcome.proposeDrafts()).hasSize(1);
        assertThat(outcome.proposeDrafts().get(0).getType()).isEqualTo(com.jungwoo.project.memo.ai.draft.DraftType.CREATE_SCHEDULE);
        assertThat(outcome.askDraft().getType()).isEqualTo(com.jungwoo.project.memo.ai.draft.DraftType.CREATE_ROUTINE);
        assertThat(outcome.askDraft().getAskCount()).isEqualTo(1);
        assertThat(sink.completed.quickReplies()).containsExactly("첫 수업 전만", "수업 전마다");
    }

    /** B-8 #12 후반: 대화 ARCHIVED → OPEN draft 전부 CANCELLED(같은 트랜잭션). */
    @Test
    void deleteConversation_cancelsOpenDrafts() {
        when(aiConversationMapper.findByIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(AiConversation.builder()
                        .conversationId(CONVERSATION_ID).userId(USER_ID)
                        .status(ConversationStatus.ACTIVE).build());

        service.deleteConversation(CONVERSATION_ID, USER_ID);

        verify(aiConversationMapper).updateStatus(CONVERSATION_ID, USER_ID, "ARCHIVED");
        verify(aiConversationDraftMapper).cancelOpenByConversation(CONVERSATION_ID, USER_ID);
    }

    private static class RecordingSink implements AiTurnEventSink {
        StringBuilder deltas = new StringBuilder();
        AiTurnCompletedPayload completed;
        ErrorCode errorCode;
        OfferAction offerAction;
        AiProposalResponse proposalReady;
        com.jungwoo.project.memo.plan.dto.PlanDraftResponse periodPlanReady;
        List<ContextSuggestionResponse> contextSuggestionsReady;
        List<ScheduleSuggestionResponse> scheduleSuggestionsReady;
        AtomicInteger startedCount = new AtomicInteger(0);
        List<String> events = new ArrayList<>();

        @Override public void onStarted(Long requestMessageId) { startedCount.incrementAndGet(); events.add("started"); }
        @Override public void onDelta(String text) { deltas.append(text); }
        @Override public void onOfferReady(OfferAction offerAction) { this.offerAction = offerAction; }
        @Override public void onProposalReady(AiProposalResponse proposal) { this.proposalReady = proposal; }
        @Override public void onPeriodPlanReady(com.jungwoo.project.memo.plan.dto.PlanDraftResponse draft) { this.periodPlanReady = draft; }
        @Override public void onContextSuggestionsReady(List<ContextSuggestionResponse> suggestions) { this.contextSuggestionsReady = suggestions; }
        @Override public void onScheduleSuggestionsReady(List<ScheduleSuggestionResponse> suggestions) { this.scheduleSuggestionsReady = suggestions; }
        @Override public void onCompleted(AiTurnCompletedPayload payload) { this.completed = payload; }
        @Override public void onError(ErrorCode errorCode) { this.errorCode = errorCode; }
    }
}
