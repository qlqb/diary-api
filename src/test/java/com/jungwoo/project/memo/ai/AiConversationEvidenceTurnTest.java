package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.consult.ConsultTurnService;
import com.jungwoo.project.memo.ai.consult.ConsultView;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.evidence.ConsultEvidenceService;
import com.jungwoo.project.memo.ai.evidence.EvidenceLedger;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.CourseService;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상담 턴의 근거 흐름: 근거 블록이 모델 입력에 실리고, 모델이 더 읽어야 한다고 하면 한 번만 더 읽고 다시 부르며, 확인한
 * 출처가 저장·재생된다. 근거 조회 자체(DB 검색)는 ConsultEvidenceDbTest가 본다.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationEvidenceTurnTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long REQUEST_MESSAGE_ID = 100L;

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

    @InjectMocks
    private AiConversationService service;

    private ConsultEvidenceService evidenceService;
    private ConsultTurnService consultTurnService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC));
        evidenceService = mock(ConsultEvidenceService.class);
        consultTurnService = mock(ConsultTurnService.class);
        ReflectionTestUtils.setField(service, "consultEvidenceService", evidenceService);
        ReflectionTestUtils.setField(service, "consultTurnService", consultTurnService);
        lenient().when(aiWorkspaceContextBuilder.build(any(), any(), any(), any(), anyBoolean())).thenReturn("[오늘 실행 상태]\n");
        lenient().when(contextSnapshotService.buildContextBlock(any(), any(), any(), anyInt(), any())).thenReturn("[최근 대화]\n");
        lenient().when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt(), any())).thenReturn(List.of());
        lenient().when(aiConversationDraftMapper.findOpenByConversationIdAndUserId(any(), any())).thenReturn(List.of());
        lenient().when(aiTurnLifecycleService.completeTurnSuccess(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any()))
                .thenReturn(new AiTurnLifecycleService.TurnCompletionResult(assistant(201L), null, List.of(), List.of()));
    }

    @Test
    void 근거_블록이_대화_기록보다_앞에_실리고_화면_상태의_앞부분_발췌는_빠진다() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(evidenceService.gather(any())).thenReturn(new ConsultEvidenceService.Gathered(
                "[자료 확인 결과]\n[E1] 자료구조_강의계획서.pdf · p.5\n| 7주차 | 10/19~10/23 | 중간고사 |\n", ledger, true));
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chat(
                "자료구조 중간고사는 10/19~10/23 시험 주간이에요(자료구조_강의계획서.pdf p.5).\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"used\":[\"E1\"],\"readMore\":[]}}")));
        when(ledger.toView(any(), any())).thenReturn(new ConsultView.Evidence("원문 1곳 확인", List.of(), List.of(), 1));

        RecordingSink sink = new RecordingSink();
        await(sink, service.streamAndComplete(prepared(), request("무슨 과목부터 공부할까"), sink));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(1)).streamTurn(any(), prompt.capture(), any(), any());
        String p = prompt.getValue();
        assertThat(p.indexOf("[자료 확인 결과]")).isGreaterThan(p.indexOf("[오늘 실행 상태]"));
        assertThat(p.indexOf("[자료 확인 결과]")).isLessThan(p.indexOf("[최근 대화]"));
        verify(aiWorkspaceContextBuilder).build(any(), any(), any(), eq(RequestedAction.AUTO), eq(false));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> used = ArgumentCaptor.forClass(Set.class);
        verify(ledger).toView(used.capture(), any());
        assertThat(used.getValue()).containsExactly("E1");
        verify(consultTurnService).finish(eq(USER_ID), eq(CONVERSATION_ID), eq(REQUEST_MESSAGE_ID), eq(201L), any(), any(),
                any(ConsultView.Evidence.class));
        assertThat(sink.completed.responseType()).isEqualTo(AiResponseType.CHAT);
    }

    @Test
    void 첫_응답이_더_읽겠다고_하면_한_번만_더_읽고_두_번째_응답이_답이_된다() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(evidenceService.gather(any())).thenReturn(new ConsultEvidenceService.Gathered("[자료 확인 결과]\n- M1 ...\n",
                ledger, true));
        when(evidenceService.readingLabel(any(), anyList())).thenReturn("자료구조_강의계획서.pdf p.3-4 확인 중");
        when(evidenceService.readMore(eq(USER_ID), eq(ledger), anyList(), anyInt())).thenReturn(new ConsultEvidenceService.ReadMoreResult(
                "[추가로 읽은 원문]\n[E2] 자료구조_강의계획서.pdf · p.3\n강의 시간: 화요일 10:00~11:50\n", 1, List.of()));
        when(ledger.toView(any(), any())).thenReturn(new ConsultView.Evidence("원문 2곳", List.of(), List.of(), 2));
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chat("강의계획서의 수업 시간 부분을 확인해 볼게요.\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"used\":[],\"readMore\":[{\"ref\":\"M1\",\"units\":\"3-4\"}]}}")))
                .thenReturn(Flux.just(chat("수업은 화요일 10:00~11:50이에요(p.3).\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"used\":[\"E2\"],\"readMore\":[{\"ref\":\"M1\",\"units\":\"9\"}]}}")));

        RecordingSink sink = new RecordingSink();
        await(sink, service.streamAndComplete(prepared(), request("자료에 있는데"), sink));

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient, times(2)).streamTurn(any(), prompts.capture(), any(), any());
        assertThat(prompts.getAllValues().get(1)).contains("[추가로 읽은 원문]").contains("강의 시간: 화요일 10:00~11:50")
                .contains("evidence.readMore를 내지 않는다");
        assertThat(sink.readingLabels).containsExactly("자료구조_강의계획서.pdf p.3-4 확인 중");
        verify(evidenceService, times(1)).readMore(any(), any(), anyList(), anyInt());

        // 저장되는 답은 두 번째 응답이다. 두 번째 응답의 추가 요청은 읽지 않고 "읽지 못한 범위"로 남는다.
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(aiTurnLifecycleService).completeTurnSuccess(any(), any(), any(), reply.capture(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertThat(reply.getValue()).startsWith("수업은 화요일");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConsultView.Gap>> gaps = ArgumentCaptor.forClass(List.class);
        verify(ledger).toView(any(), gaps.capture());
        assertThat(gaps.getValue()).extracting(ConsultView.Gap::reason).containsExactly("NOT_READ_LIMIT");
        // 한도에 세는 상담 호출은 1번, 추가 읽기는 따로 남는다.
        verify(aiUsageLimitService, times(1)).record(any(), any(), any(), any(), any(), any(), any(),
                eq(UsageResultStatus.SUCCESS), isNull());
        verify(aiUsageLimitService, times(1)).record(any(), any(), any(), any(), any(), any(), any(),
                eq(UsageResultStatus.SUCCESS), isNull(), eq(AiConversationService.READ_MORE_FEATURE), any(), any(), any());
    }

    @Test
    void 첫_조회에서_걸린_것이_없어도_추가로_읽었으면_출처를_남긴다() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.rounds()).thenReturn(2);
        when(evidenceService.gather(any())).thenReturn(new ConsultEvidenceService.Gathered("[자료 목록]\n- M1 ...\n", ledger,
                false));
        when(evidenceService.readingLabel(any(), anyList())).thenReturn("\"성적\" 검색 확인 중");
        when(evidenceService.readMore(eq(USER_ID), eq(ledger), anyList(), anyInt())).thenReturn(
                new ConsultEvidenceService.ReadMoreResult("[추가로 읽은 원문]\n[E1] 출석 10%\n", 1, List.of()));
        when(ledger.toView(any(), any())).thenReturn(new ConsultView.Evidence("원문 1곳", List.of(), List.of(), 2));
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chat("평가 기준을 확인해 볼게요.\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"readMore\":[{\"query\":\"성적 평가 비율\"}]}}")))
                .thenReturn(Flux.just(chat("출석 10%예요.\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"used\":[\"E1\"]}}")));

        RecordingSink sink = new RecordingSink();
        await(sink, service.streamAndComplete(prepared(), request("성적은 어떻게 매겨?"), sink));

        verify(consultTurnService).finish(any(), any(), any(), any(), any(), any(), any(ConsultView.Evidence.class));
    }

    @Test
    void 근거_블록이_없으면_추가_읽기_요청은_무시하고_한_번으로_끝난다() {
        when(evidenceService.gather(any())).thenReturn(ConsultEvidenceService.Gathered.none());
        when(aiConsultationClient.streamTurn(any(), any(), any(), any())).thenReturn(Flux.just(chat(
                "그렇구나.\n<<<AI_STRUCTURED>>>\n{\"decision\":\"CHAT\",\"evidence\":{\"readMore\":[{\"ref\":\"M1\"}]}}")));

        RecordingSink sink = new RecordingSink();
        await(sink, service.streamAndComplete(prepared(), request("오늘 좀 피곤해"), sink));

        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(evidenceService, never()).readMore(any(), any(), anyList(), anyInt());
        assertThat(sink.completed).isNotNull();
    }

    @Test
    void 입력_안내_선택지를_답으로_보내면_받지_않고_자료_찾기는_조회를_넓힌다() {

        AiMessageRequest input = request(null);
        input.setAnswer(new AiMessageRequest.Answer("q-5", List.of("c2"), false, false));
        when(consultTurnService.compose(USER_ID, CONVERSATION_ID, "q-5", List.of("c2"), false, false)).thenReturn(null);

        assertThatThrownBy(() -> service.prepareTurn(CONVERSATION_ID, USER_ID, input))
                .isInstanceOf(BadRequestException.class);

        AiMessageRequest lookup = request("내 자료에서 찾아봐");
        lookup.setAnswer(new AiMessageRequest.Answer("q-5", List.of(), false, true));
        when(consultTurnService.compose(USER_ID, CONVERSATION_ID, "q-5", List.of(), false, true))
                .thenReturn(new ConsultTurnService.Composed(ConsultTurnService.LOOKUP_MESSAGE, true));
        service.prepareTurn(CONVERSATION_ID, USER_ID, lookup);

        assertThat(lookup.getMessage()).isEqualTo(ConsultTurnService.LOOKUP_MESSAGE);
        assertThat(lookup.evidenceLookupRequested()).isTrue();
    }

    @Test
    void 재생하면_질문과_출처도_함께_돌아온다() {
        AiMessage userMessage = AiMessage.builder().messageId(REQUEST_MESSAGE_ID).userId(USER_ID)
                .conversationId(CONVERSATION_ID).role(MessageRole.USER).status(MessageStatus.COMPLETED).build();
        AiMessage reply = assistant(201L);
        reply.setContent("자료구조 시험은 10/19~10/23이에요.");
        reply.setResponseType(AiResponseType.CHAT);
        reply.setConsultJson("{\"evidence\":{\"summary\":\"원문 1곳\"}}");
        when(aiMessageMapper.findByReplyToMessageIdAndUserId(REQUEST_MESSAGE_ID, USER_ID)).thenReturn(reply);
        ConsultView stored = new ConsultView(null, List.of(), null, null,
                new ConsultView.Evidence("원문 1곳", List.of(), List.of(), 1));
        when(consultTurnService.read(any())).thenReturn(stored);

        RecordingSink sink = new RecordingSink();
        service.replayStoredTurn(new AiTurnLifecycleService.PreparedTurn(conversation(), REQUEST_MESSAGE_ID, true,
                userMessage), sink);

        assertThat(sink.completed.consult()).isSameAs(stored);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), any(), any());
    }

    @Test
    void 답변_문장의_내부_근거_번호는_지운다() {
        assertThat(AiConversationService.stripEvidenceRefs("localhost:3000에서 확인하면 됩니다. (E2)"))
                .isEqualTo("localhost:3000에서 확인하면 됩니다.");
        assertThat(AiConversationService.stripEvidenceRefs("10월 10일까지예요(자료구조 강의계획서 p.5, E1)."))
                .isEqualTo("10월 10일까지예요(자료구조 강의계획서 p.5).");
        assertThat(AiConversationService.stripEvidenceRefs("수업[F1, F2]은 화요일이에요"))
                .isEqualTo("수업은 화요일이에요");
        assertThat(AiConversationService.stripEvidenceRefs("3장(스택)부터 보세요")).isEqualTo("3장(스택)부터 보세요");
    }

    @Test
    void 두_번째_호출은_조각이_계속_와도_턴_마감에_시간_초과로_끝난다() {
        Flux<ChatResponse> endless = Flux.interval(java.time.Duration.ofMillis(20)).map(i -> chat("…"));
        long deadline = System.nanoTime() + java.time.Duration.ofMillis(200).toNanos();

        long started = System.nanoTime();
        assertThatThrownBy(() -> AiConversationService.withDeadline(endless, deadline).blockLast(java.time.Duration.ofSeconds(3)))
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        // 마감 전에 끝나는 스트림은 그대로 정상 완료다.
        assertThat(AiConversationService.withDeadline(Flux.just(chat("끝")),
                System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()).collectList().block()).hasSize(1);
    }

    @Test
    void 연결이_끊기면_첫_호출_완료_뒤에_시작된_두_번째_호출도_취소된다() throws Exception {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(evidenceService.gather(any())).thenReturn(new ConsultEvidenceService.Gathered("[자료 확인 결과]\n", ledger, true));
        when(evidenceService.readingLabel(any(), anyList())).thenReturn("확인 중");
        when(evidenceService.readMore(any(), any(), anyList(), anyInt()))
                .thenReturn(new ConsultEvidenceService.ReadMoreResult("[추가로 읽은 원문]\n", 1, List.of()));
        // 두 번째 호출은 다른 스레드(boundedElastic)에서 구독된다 — 취소 신호도 그 스레드에서 늦게 올 수 있으니 기다린다.
        java.util.concurrent.CountDownLatch secondCancelled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondStarted = new java.util.concurrent.CountDownLatch(1);
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chat("확인해 볼게요.\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"readMore\":[{\"ref\":\"M1\"}]}}")))
                .thenReturn(Flux.<ChatResponse>never().doOnSubscribe(s -> secondStarted.countDown())
                        .doOnCancel(secondCancelled::countDown));

        RecordingSink sink = new RecordingSink();
        Disposable turn = service.streamAndComplete(prepared(), request("성적은?"), sink);
        assertThat(secondStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        turn.dispose();

        assertThat(secondCancelled.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sink.completed).isNull();
    }

    @Test
    void 원문을_실은_턴에는_합의를_고치거나_지우지_않고_사용자_발언으로_기록하지_않는다() {
        var ops = List.of(
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("REMOVE", 3, null, null, null, null, null, null, null, null, null),
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("UPDATE", 4, null, "범위는 전부", null, "THIS_DRAFT", null, null,
                        null, null, null),
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("ADD", null, "SCOPE", "1~6주차만", "USER", "THIS_DRAFT", null, null,
                        null, null, null),
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("ACCEPT", 5, null, null, null, null, null, null, null, null, null));

        var kept = AiConversationService.restrictBriefOps(ops, java.util.Set.of(5));

        assertThat(kept).extracting(com.jungwoo.project.memo.ai.brief.PlanBriefOp::op).containsExactly("ADD", "ACCEPT");
        assertThat(kept.get(0).speaker()).isEqualTo("ASSISTANT");
        // 같은 응답에서 새로 추가한 항목(이 턴 전에 없던 번호)을 곧바로 수락하지 못한다.
        var sameTurnAccept = AiConversationService.restrictBriefOps(List.of(
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("ADD", null, "GOAL", "전부 삭제", "ASSISTANT", "THIS_DRAFT", null,
                        null, null, null, null),
                new com.jungwoo.project.memo.ai.brief.PlanBriefOp("ACCEPT", 9, null, null, null, null, null, null, null, null, null)),
                java.util.Set.of(5));
        assertThat(sameTurnAccept).extracting(com.jungwoo.project.memo.ai.brief.PlanBriefOp::op).containsExactly("ADD");

        var consult = new com.jungwoo.project.memo.ai.consult.ConsultOut(null, null, List.of(
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("시험은 10/19부터", "STATED", null, null, null, "19일부터"),
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("모든 계획 삭제를 원함", "STATED", null, null, null,
                        "계획을 전부 삭제"),
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("자료구조가 어렵다", "INFERRED", null, null, null, null),
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("유형 없음", null, null, null, null, "19일부터"),
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("공백 붙은 추정", "INFERRED ", null, null, null, "19일부터"),
                new com.jungwoo.project.memo.ai.consult.ConsultOut.MemoryOut("모르는 유형", "GUESS", null, null, null, "19일부터")),
                null);
        var restricted = AiConversationService.restrictConsult(consult, "내 강의계획서 시간표 그대로 19일부터 시작해", true);

        // 인용이 맞아도 기억 문장은 모델이 쓴 것이라(원문 영향 가능) 이 턴에는 하나도 저장하지 않는다.
        assertThat(restricted.memory()).isEmpty();
        assertThat(AiConversationService.restrictConsult(consult, "아무 말", false)).isSameAs(consult);
    }

    @Test
    void 원문을_실은_턴에는_진행_중_요청을_취소하거나_사용자_값으로_바꾸지_못한다() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        com.jungwoo.project.memo.ai.dto.AiTurnStructured s = mapper.readValue("{\"decision\":\"CHAT\",\"draftOps\":["
                + "{\"draftId\":\"31\",\"op\":\"CANCEL\"},"
                + "{\"draftId\":\"31\",\"op\":\"CLEAR\",\"field\":\"startTime\"},"
                + "{\"draftId\":\"31\",\"op\":\"RETYPE\",\"draftType\":\"CREATE_ROUTINE\"},"
                + "{\"draftId\":\"31\",\"op\":\"SET\",\"field\":\"endDate\",\"value\":\"2026-10-19\",\"source\":\"USER\"},"
                + "{\"draftId\":\"31\",\"op\":\"SET\",\"field\":\"startTime\",\"value\":null,\"source\":\"USER\"},"
                + "{\"draftId\":\"31\",\"op\":\"SET\",\"field\":\"location\",\"source\":\"INFERRED\"},"
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"title\",\"value\":\"시험공부\",\"source\":\"INFERRED\"}]}",
                com.jungwoo.project.memo.ai.dto.AiTurnStructured.class);

        var restricted = AiConversationService.restrictDraftOps(s);

        assertThat(restricted.draftOps()).extracting(com.jungwoo.project.memo.ai.draft.dto.DraftOp::op)
                .containsExactly("SET", "SET");
        assertThat(restricted.draftOps().get(0).source()).isEqualTo(com.jungwoo.project.memo.ai.draft.FieldSource.INFERRED);
        assertThat(restricted.draftOps().get(0).confirmationRequired()).isTrue();
        assertThat(restricted.decision()).isEqualTo(s.decision());
    }

    @Test
    void 추가_읽기를_실을_입력_여유가_없으면_두_번째_호출을_하지_않고_그렇게_말한다() {
        ReflectionTestUtils.setField(service, "maxInputTokens", 900); // 3,600자: 대화 기록 최소 몫을 빼면 추가 읽기 자리가 없다
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(evidenceService.gather(any())).thenReturn(new ConsultEvidenceService.Gathered("[자료 확인 결과]\n", ledger, true));
        when(ledger.toView(any(), any())).thenReturn(new ConsultView.Evidence("원문 0곳", List.of(), List.of(), 1));
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chat("평가 기준을 확인해 볼게요.\n<<<AI_STRUCTURED>>>\n"
                        + "{\"decision\":\"CHAT\",\"evidence\":{\"readMore\":[{\"ref\":\"M1\"}]}}")));

        RecordingSink sink = new RecordingSink();
        await(sink, service.streamAndComplete(prepared(), request("성적은?"), sink));

        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), any(), any());
        verify(evidenceService, never()).readMore(any(), any(), anyList(), anyInt());
        assertThat(sink.completed.reply()).contains("한도 안에서 읽지 못했어요");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConsultView.Gap>> gaps = ArgumentCaptor.forClass(List.class);
        verify(ledger).toView(any(), gaps.capture());
        assertThat(gaps.getValue()).extracting(ConsultView.Gap::reason).containsExactly("NOT_READ_LIMIT");
    }

    @Test
    void 첫_호출도_조각이_계속_오면_턴_마감에_실패로_끝난다() {
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 1);
        when(evidenceService.gather(any())).thenReturn(ConsultEvidenceService.Gathered.none());
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.interval(java.time.Duration.ofMillis(50)).map(i -> chat("계속 ")));

        RecordingSink sink = new RecordingSink();
        long started = System.nanoTime();
        Disposable d = service.streamAndComplete(prepared(), request("안녕"), sink);
        long deadline = System.currentTimeMillis() + 4000;
        while (sink.errorCode == null && sink.completed == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        d.dispose();

        assertThat(sink.errorCode).isNotNull();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(3));
        verify(aiTurnLifecycleService).completeTurnFailure(CONVERSATION_ID, USER_ID, REQUEST_MESSAGE_ID);
    }

    // ===== 도구 =====

    private AiConversation conversation() {
        return AiConversation.builder().conversationId(CONVERSATION_ID).userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY).status(ConversationStatus.ACTIVE).build();
    }

    private AiTurnLifecycleService.PreparedTurn prepared() {
        return new AiTurnLifecycleService.PreparedTurn(conversation(), REQUEST_MESSAGE_ID, false, null);
    }

    private AiMessage assistant(Long id) {
        return AiMessage.builder().messageId(id).userId(USER_ID).conversationId(CONVERSATION_ID)
                .role(MessageRole.ASSISTANT).status(MessageStatus.COMPLETED).build();
    }

    private AiMessageRequest request(String message) {
        return AiMessageRequest.builder().message(message).requestedAction(RequestedAction.AUTO)
                .idempotencyKey("k-" + System.nanoTime()).build();
    }

    private ChatResponse chat(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void await(RecordingSink sink, Disposable d) {
        long deadline = System.currentTimeMillis() + 5000;
        while (sink.completed == null && sink.errorCode == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(sink.errorCode).as("턴 실패").isNull();
        d.dispose();
    }

    private static class RecordingSink implements AiTurnEventSink {
        StringBuilder deltas = new StringBuilder();
        List<String> readingLabels = new ArrayList<>();
        AiTurnCompletedPayload completed;
        ErrorCode errorCode;

        @Override public void onStarted(Long requestMessageId) { }
        @Override public void onDelta(String text) { deltas.append(text); }
        @Override public void onOfferReady(OfferAction offerAction) { }
        @Override public void onProposalReady(AiProposalResponse proposal) { }
        @Override public void onContextSuggestionsReady(List<ContextSuggestionResponse> suggestions) { }
        @Override public void onScheduleSuggestionsReady(List<ScheduleSuggestionResponse> suggestions) { }
        @Override public void onEvidenceReading(String label) { readingLabels.add(label); }
        @Override public void onCompleted(AiTurnCompletedPayload payload) { this.completed = payload; }
        @Override public void onError(ErrorCode errorCode) { this.errorCode = errorCode; }
    }
}
