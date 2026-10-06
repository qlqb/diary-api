package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.dto.AiConversationCreateRequest;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.PeriodPlanRequest;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.PlanConfirmService;
import com.jungwoo.project.memo.plan.PlanDraftService;
import com.jungwoo.project.memo.plan.PlanSnapshotCodec;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * AI 대화에서 만든 기간 계획이 계획 화면과 <b>같은 확정 결과</b>를 남기는지를 실제 로컬
 * MariaDB(memo)에 대고 끝까지 검증한다.
 *
 * <p>덮는 경로: 오늘 탭 scope 대화 생성 → 기간 계획 요청(AUTO) → 서버가 만든
 * CREATE_PERIOD_PLAN 버튼 → 그 버튼으로 재요청 → period_plan.ready → 항목 시간 편집 →
 * confirm → plan_versions 1건 + execution_items(편집된 시각) + 제안 APPLIED.
 *
 * <p>Mockito 단위 테스트로는 증명할 수 없는 것이 대상이다 — 대화 턴의 트랜잭션과 확정
 * 트랜잭션이 실제로 같은 행들을 만드는지, 실패할 때 정말 아무것도 남지 않는지, 대화에서 만든
 * 계획과 계획 화면에서 만든 계획의 확정 결과 구조가 같은지.
 *
 * <p>외부 AI는 부르지 않는다. AiConsultationClient만 목으로 갈아끼워 상담 응답과 계획 생성
 * 응답을 고정 JSON으로 준다 — 나머지(가용시간 추정, 프롬프트 조립, 정규화, 저장, 확정)는 전부
 * 실제 코드다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
/*
 * ★ 생성 경로를 고정한다. plan.draft.generator는 application-local.properties(gitignore)에서
 * 바뀔 수 있고, 그 값이 V1이면 판단층이 되묻기를 내면서 proposal이 null로 온다 — 이 스위트가
 * 검증하는 것은 대화→확정 경로이지 어느 생성기를 쓰는가가 아니다. 추적되지 않는 파일에
 * 결과가 좌우되는 테스트는 그 자체로 결함이라 여기서 못 박는다.
 */
@TestPropertySource(properties = "plan.draft.generator=AI")
@SpringBootTest
class PeriodPlanConversationIntegrationTest {

    private static final String TITLE_PREFIX = "PPCI-";
    /** 명세가 요구하는 최소 규모. 일반 제안의 5개 상한을 넘는다는 것 자체가 검증 대상이다. */
    private static final int ITEM_COUNT = 9;

    @MockitoBean
    private AiConsultationClient aiConsultationClient;

    @Autowired
    private AiConversationService aiConversationService;
    @Autowired
    private AiTurnLifecycleService aiTurnLifecycleService;
    @Autowired
    private AiProposalService aiProposalService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private PlanDraftService planDraftService;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private PlanSnapshotCodec snapshotCodec;
    @Autowired
    private ExecutionItemMapper executionItemMapper;
    @Autowired
    private DataSource dataSource;

    private Long userId;
    private final List<Long> createdConversationIds = new ArrayList<>();
    private final List<Long> createdProposalIds = new ArrayList<>();
    /**
     * 테스트 시작 시점의 마지막 사용량 로그 id. 계획 생성 사용량은 conversation_id가 NULL이라
     * 대화 id로 지울 수 없어서, 이 id보다 큰 <b>이 사용자</b>의 행만 지운다. 테스트를 돌리는
     * 동안 같은 계정으로 앱을 쓰지 않는다고 가정한다(기존 DB 통합 테스트와 같은 전제다).
     */
    private long usageLogWatermark;

    @BeforeEach
    void setUpAiClient() {
        usageLogWatermark = maxUsageLogId();
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        // 상담 턴(2인자): 기간 계획 목적과 기간·강도를 명시한 OFFER.
        when(aiConsultationClient.streamTurn(any(), any(), any(), any()))
                .thenReturn(Flux.just(chatResponse(offerJson())));
        // 계획 생성 턴(3인자): 9개 항목. PlanDraftService가 부르는 쪽이다.
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse(planJson())));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM execution_item_events WHERE execution_item_id IN "
                    + "(SELECT execution_item_id FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%')");
            exec(conn, "DELETE FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%'");
            exec(conn, "DELETE FROM plan_versions WHERE title LIKE '" + TITLE_PREFIX + "%'");
            for (Long proposalId : createdProposalIds) {
                exec(conn, "DELETE FROM ai_proposal_items WHERE proposal_id = " + proposalId);
                exec(conn, "DELETE FROM ai_proposals WHERE proposal_id = " + proposalId);
            }
            // 계획 생성 사용량은 conversation_id가 NULL이라 아래 대화별 삭제로 지워지지 않는다.
            exec(conn, "DELETE FROM ai_usage_logs WHERE usage_log_id > " + usageLogWatermark
                    + " AND user_id = " + userId());
            for (Long conversationId : createdConversationIds) {
                exec(conn, "DELETE FROM ai_usage_logs WHERE conversation_id = " + conversationId);
                exec(conn, "DELETE FROM ai_proposal_items WHERE proposal_id IN "
                        + "(SELECT proposal_id FROM ai_proposals WHERE conversation_id = " + conversationId + ")");
                exec(conn, "DELETE FROM ai_proposals WHERE conversation_id = " + conversationId);
                exec(conn, "DELETE FROM ai_messages WHERE conversation_id = " + conversationId);
                exec(conn, "DELETE FROM ai_conversations WHERE conversation_id = " + conversationId);
            }
        }
        createdProposalIds.clear();
        createdConversationIds.clear();
    }

    // ===== 전체 경로 =====

    /**
     * 오늘 탭에서 시작한 기간 계획이 계획 화면과 같은 곳에 도착한다. 항목 수는 일반 제안의
     * 5개 상한을 넘고, 확정하면 PlanVersion 1건과 편집한 시각 그대로의 실행 조각이 남는다.
     */
    @Test
    void fromTodayTabConversation_toConfirmedPlanVersion_withEditedTimes() {
        Long conversationId = givenTodayConversation();

        // 1. 기간 계획 요청 → 서버가 검증해 만든 CREATE_PERIOD_PLAN 버튼.
        RecordingSink offerTurn = runTurn(conversationId, autoMessage("이번 주 계획 짜줘"));
        assertThat(offerTurn.completed.responseType()).isEqualTo(AiResponseType.OFFER);
        OfferAction offer = offerTurn.offerAction;
        assertThat(offer).isNotNull();
        assertThat(offer.type()).isEqualTo(OfferAction.TYPE_CREATE_PERIOD_PLAN);
        assertThat(offer.intensity()).isEqualTo(PlanIntensity.NORMAL);
        assertThat(offer.periodStartDate()).isEqualTo(today());
        assertThat(offer.periodEndDate()).isEqualTo(today().plusDays(6));

        // 2. 버튼 클릭 → 계획 화면과 같은 생성기. 상담 모델을 다시 부르지 않는다.
        RecordingSink planTurn = runTurn(conversationId, periodPlanMessage(offer));
        assertThat(planTurn.errorCode).as("오류 없이 끝난다").isNull();
        PlanDraftResponse draft = planTurn.periodPlanReady;
        assertThat(draft).as("period_plan.ready로 초안이 온다").isNotNull();
        assertThat(planTurn.completed.responseType()).isEqualTo(AiResponseType.PROPOSAL);
        assertThat(planTurn.completed.periodPlanDraft()).isNotNull();
        assertThat(planTurn.completed.offerAction()).as("초안을 만든 턴에는 버튼이 없다").isNull();
        createdProposalIds.add(draft.getProposalId());

        List<AiProposalItemResponse> items = draft.getProposal().getItems();
        assertThat(items).as("일반 제안의 5개 상한이 아니라 기간 계획 상한을 쓴다")
                .hasSize(ITEM_COUNT);
        assertThat(draft.getIntensity()).isEqualTo(PlanIntensity.NORMAL);
        assertThat(draft.getTargetMinutes()).isPositive();
        assertThat(draft.getEstimatedAvailableMinutes()).isPositive();

        // 제안이 이 대화에 연결돼 있다 — 새로고침 후에도 어느 대화에서 나왔는지 남는다.
        AiProposal stored = aiProposalMapper.findByIdAndUserId(draft.getProposalId(), userId());
        assertThat(stored.getConversationId()).isEqualTo(conversationId);
        assertThat(stored.getPlanStartDate()).isEqualTo(today());
        assertThat(stored.getPlanEndDate()).isEqualTo(today().plusDays(6));
        assertThat(stored.getPlanIntensity()).isEqualTo(PlanIntensity.NORMAL);
        assertThat(stored.getPlanTargetMinutes()).isEqualTo(draft.getTargetMinutes());

        // 3. 두 항목의 시각을 사용자가 편집한다(미리보기가 정한 시각을 그대로 싣는 경로와 같다).
        LocalDateTime firstStart = today().plusDays(1).atTime(19, 0);
        LocalDateTime secondStart = today().plusDays(2).atTime(20, 0);
        List<AiProposalApplyRequest.EditedProposalItem> edits = List.of(
                editedTime(items.get(0).getProposalItemId(), firstStart, firstStart.plusMinutes(40)),
                editedTime(items.get(1).getProposalItemId(), secondStart, secondStart.plusMinutes(30)));

        PlanVersion plan = planConfirmService.confirm(userId(), draft.getProposalId(),
                PlanConfirmRequest.builder()
                        .title(TITLE_PREFIX + "대화 기간 계획")
                        .editedItems(edits)
                        .build());

        // 4. PlanVersion 정확히 1건.
        assertThat(countRows("plan_versions", "source_proposal_id = " + draft.getProposalId())).isEqualTo(1);
        assertThat(plan.getVersion()).isEqualTo(1);
        assertThat(plan.getIntensity()).isEqualTo(PlanIntensity.NORMAL);
        assertThat(plan.getTargetMinutes()).isEqualTo(draft.getTargetMinutes());

        // 5. 실행 조각이 편집한 시각 그대로 생겼다.
        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(plan.getItemsSnapshot());
        assertThat(snapshot).hasSize(ITEM_COUNT);
        List<Long> ids = snapshot.stream().map(PlanSnapshotItem::executionItemId).toList();
        List<ExecutionItem> created = executionItemMapper.findByIdsForReview(userId(), ids);
        assertThat(created).hasSize(ITEM_COUNT);
        assertThat(countRows("execution_items", "plan_version_id = " + plan.getPlanVersionId()))
                .isEqualTo(ITEM_COUNT);

        ExecutionItem first = created.stream()
                .filter(i -> i.getScheduledStartAt() != null && i.getScheduledStartAt().equals(firstStart))
                .findFirst().orElseThrow(() -> new AssertionError("편집한 시각의 실행 조각이 없다"));
        assertThat(first.getPlacementType()).isEqualTo(PlacementType.TIME_FIXED);
        assertThat(first.getScheduledEndAt()).isEqualTo(firstStart.plusMinutes(40));
        assertThat(first.getScheduledDate()).isEqualTo(firstStart.toLocalDate());
        // 시각이 정해진 조각의 길이는 구간에서 계산된다(PlacementDuration).
        assertThat(first.getExpectedMinutes()).isEqualTo(40);

        // 편집하지 않은 항목은 미배치로 남고 계획 기간을 배치 범위로 갖는다.
        List<ExecutionItem> unscheduled = created.stream()
                .filter(i -> i.getPlacementType() == PlacementType.UNSCHEDULED).toList();
        assertThat(unscheduled).hasSize(ITEM_COUNT - 2);
        assertThat(unscheduled).allSatisfy(item -> {
            assertThat(item.getPlanningStartDate()).isEqualTo(today());
            assertThat(item.getPlanningEndDate()).isEqualTo(today().plusDays(6));
        });

        // 6. 제안은 응답 완료 상태다 — 같은 초안이 목록에 계속 "검토 전"으로 남지 않는다.
        AiProposal applied = aiProposalMapper.findByIdAndUserId(draft.getProposalId(), userId());
        assertThat(applied.getStatus())
                .isIn(AiProposalStatus.APPLIED, AiProposalStatus.MODIFIED_APPLIED);
    }

    @Test
    void confirmingTheSameConversationPlanTwice_isRejected() {
        PlanDraftResponse draft = givenConversationPlanDraft();

        planConfirmService.confirm(userId(), draft.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "첫 확정").build());

        assertThatThrownBy(() -> planConfirmService.confirm(userId(), draft.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "두 번째 확정").build()))
                .isInstanceOf(ConflictException.class);

        assertThat(countRows("plan_versions", "source_proposal_id = " + draft.getProposalId()))
                .as("두 번째 시도가 계획을 하나 더 만들지 않는다").isEqualTo(1);
    }

    /**
     * 지난 시각으로 편집하면 확정 전체가 막힌다. PlanVersion도 실행 조각도 남지 않고 제안은
     * 그대로 검토 전이다 — "계획만 생기고 조각은 없는" 상태가 만들어지지 않는다.
     */
    @Test
    void confirmWithAPastTime_rollsBackEverything() {
        PlanDraftResponse draft = givenConversationPlanDraft();
        Long proposalId = draft.getProposalId();
        LocalDateTime past = today().minusDays(1).atTime(19, 0);

        assertThatThrownBy(() -> planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder()
                        .title(TITLE_PREFIX + "지난 시각")
                        .editedItems(List.of(editedTime(
                                draft.getProposal().getItems().get(0).getProposalItemId(),
                                past, past.plusMinutes(40))))
                        .build()))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PLAN_PLACEMENT_IN_PAST));

        assertThat(countRows("plan_versions", "source_proposal_id = " + proposalId))
                .as("PlanVersion이 남지 않는다").isZero();
        assertThat(countRows("execution_items", "title LIKE '" + TITLE_PREFIX + "%'"))
                .as("실행 조각이 남지 않는다").isZero();
        AiProposal after = aiProposalMapper.findByIdAndUserId(proposalId, userId());
        assertThat(after.getStatus()).as("제안은 그대로 검토 전이다").isEqualTo(AiProposalStatus.PROPOSED);
    }

    /**
     * 실패한 대화 턴은 AI 메시지도 제안도 남기지 않는다. 기간이 잘못된 요청은 잠그기 전에
     * 걸러지므로 PROCESSING 행조차 생기지 않는다.
     */
    @Test
    void aFailedPeriodPlanTurn_leavesNeitherMessageNorProposal() {
        Long conversationId = givenTodayConversation();
        int messagesBefore = countRows("ai_messages", "conversation_id = " + conversationId);

        AiMessageRequest bad = AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PERIOD_PLAN)
                .idempotencyKey("ppci-bad-" + System.nanoTime())
                // 32일 — 기간 상한을 넘는다.
                .periodPlan(new PeriodPlanRequest(today(), today().plusDays(32), PlanIntensity.NORMAL, List.of()))
                .build();

        assertThatThrownBy(() -> aiConversationService.prepareTurn(conversationId, userId(), bad))
                .isInstanceOf(BadRequestException.class);

        assertThat(countRows("ai_messages", "conversation_id = " + conversationId))
                .as("PROCESSING 메시지도 남지 않는다").isEqualTo(messagesBefore);
        assertThat(countRows("ai_proposals", "conversation_id = " + conversationId))
                .as("제안도 없다").isZero();
    }

    /**
     * 계획 탭에서 직접 만든 계획과 대화에서 만든 계획의 확정 결과가 같은 구조다. 문장이 같다는
     * 뜻이 아니라, 같은 메타데이터·같은 스냅샷 규칙·같은 실행 조각 연결을 갖는다는 뜻이다.
     */
    @Test
    void planTabAndConversation_produceTheSameConfirmedStructure() {
        PlanDraftResponse fromConversation = givenConversationPlanDraft();
        PlanVersion conversationPlan = planConfirmService.confirm(userId(), fromConversation.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "대화 쪽").build());

        PlanDraftResponse fromPlanTab = planDraftService.createDraft(userId(), PlanDraftRequest.builder()
                .startDate(today()).endDate(today().plusDays(6)).intensity(PlanIntensity.NORMAL).build());
        createdProposalIds.add(fromPlanTab.getProposalId());
        PlanVersion planTabPlan = planConfirmService.confirm(userId(), fromPlanTab.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "계획 탭 쪽").build());

        // 초안 단계에서 같은 계약.
        assertThat(fromConversation.getDays()).isEqualTo(fromPlanTab.getDays());
        assertThat(fromConversation.getIntensity()).isEqualTo(fromPlanTab.getIntensity());
        assertThat(fromConversation.getTargetMinutes()).isEqualTo(fromPlanTab.getTargetMinutes());
        assertThat(fromConversation.getProposal().getItems())
                .hasSameSizeAs(fromPlanTab.getProposal().getItems());

        // 확정 결과도 같은 모양.
        assertThat(conversationPlan.getIntensity()).isEqualTo(planTabPlan.getIntensity());
        assertThat(conversationPlan.getTargetMinutes()).isEqualTo(planTabPlan.getTargetMinutes());
        assertThat(conversationPlan.getStartDate()).isEqualTo(planTabPlan.getStartDate());
        assertThat(conversationPlan.getEndDate()).isEqualTo(planTabPlan.getEndDate());
        assertThat(conversationPlan.getVersion()).isEqualTo(planTabPlan.getVersion()).isEqualTo(1);
        assertThat(snapshotCodec.fromJson(conversationPlan.getItemsSnapshot()))
                .hasSameSizeAs(snapshotCodec.fromJson(planTabPlan.getItemsSnapshot()));
        assertThat(countRows("execution_items", "plan_version_id = " + conversationPlan.getPlanVersionId()))
                .isEqualTo(countRows("execution_items", "plan_version_id = " + planTabPlan.getPlanVersionId()));

        // 다른 점은 출처뿐이다 — 대화 쪽 제안만 대화에 연결돼 있다.
        assertThat(aiProposalMapper.findByIdAndUserId(fromConversation.getProposalId(), userId())
                .getConversationId()).isNotNull();
        assertThat(aiProposalMapper.findByIdAndUserId(fromPlanTab.getProposalId(), userId())
                .getConversationId()).isNull();
    }

    // ===== fixture =====

    private Long givenTodayConversation() {
        AiConversationResponse conversation = aiConversationService.createConversation(
                userId(), AiConversationCreateRequest.builder().scope(AiProposalTargetScope.TODAY).build());
        createdConversationIds.add(conversation.getConversationId());
        return conversation.getConversationId();
    }

    /** OFFER를 거쳐 실제로 초안까지 만든 상태. 확정만 남는다. */
    private PlanDraftResponse givenConversationPlanDraft() {
        Long conversationId = givenTodayConversation();
        RecordingSink offerTurn = runTurn(conversationId, autoMessage("이번 주 계획 짜줘"));
        RecordingSink planTurn = runTurn(conversationId, periodPlanMessage(offerTurn.offerAction));
        PlanDraftResponse draft = planTurn.periodPlanReady;
        assertThat(draft).isNotNull();
        createdProposalIds.add(draft.getProposalId());
        return draft;
    }

    private AiMessageRequest autoMessage(String text) {
        return AiMessageRequest.builder()
                .message(text)
                .requestedAction(RequestedAction.AUTO)
                .idempotencyKey("ppci-auto-" + System.nanoTime())
                .build();
    }

    private AiMessageRequest periodPlanMessage(OfferAction offer) {
        return AiMessageRequest.builder()
                .requestedAction(RequestedAction.CREATE_PERIOD_PLAN)
                .idempotencyKey("ppci-plan-" + System.nanoTime())
                .periodPlan(new PeriodPlanRequest(offer.periodStartDate(), offer.periodEndDate(),
                        offer.intensity(), offer.courseIds()))
                .build();
    }

    /** prepareTurn + streamAndComplete를 실제로 태우고 끝날 때까지 기다린다. */
    private RecordingSink runTurn(Long conversationId, AiMessageRequest request) {
        AiTurnLifecycleService.PreparedTurn prepared =
                aiTurnLifecycleService.prepareTurn(conversationId, userId(), request);
        RecordingSink sink = new RecordingSink();
        Disposable disposable = aiConversationService.streamAndComplete(prepared, request, sink);
        long deadline = System.currentTimeMillis() + 20_000;
        while (sink.completed == null && sink.errorCode == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        disposable.dispose();
        assertThat(sink.completed != null || sink.errorCode != null).as("턴이 끝나야 한다").isTrue();
        return sink;
    }

    private AiProposalApplyRequest.EditedProposalItem editedTime(Long itemId, LocalDateTime from, LocalDateTime to) {
        AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(itemId);
        edit.setPlacementType(PlacementType.TIME_FIXED);
        edit.setScheduledDate(from.toLocalDate());
        edit.setScheduledStartAt(from);
        edit.setScheduledEndAt(to);
        return edit;
    }

    /** 상담 모델의 응답: 기간 계획 목적 + 기간·강도. 서버가 이 값을 검증해 버튼을 만든다. */
    private String offerJson() {
        return "이번 주로 잡아볼까요?\n" + AiStreamParser.DELIMITER + "\n"
                + "{\"decision\":\"OFFER_PROPOSAL\",\"proposalPurpose\":\"PERIOD_PLAN\","
                + "\"planIntensity\":\"NORMAL\","
                + "\"periodStartDate\":\"" + today() + "\","
                + "\"periodEndDate\":\"" + today().plusDays(6) + "\","
                + "\"targetCourseIds\":[],\"proposalItems\":[],\"adjustments\":[],"
                + "\"missingInformation\":[],\"unavailableWindows\":[],\"contextChanges\":[],"
                + "\"scheduleSuggestions\":[]}";
    }

    /** 계획 생성 모델의 응답: 9개 항목. 일반 제안의 5개 상한을 넘는다. */
    private String planJson() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < ITEM_COUNT; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"title\":\"").append(TITLE_PREFIX).append("항목").append(i).append('"')
                    .append(",\"description\":\"코드 3개 작성 · 완료: 전부 실행됨\"")
                    .append(",\"expectedMinutes\":").append(30 + (i % 3) * 15)
                    .append(",\"priority\":\"SHOULD\",\"courseId\":null,\"scheduledDate\":null")
                    .append(",\"reason\":\"이번 주 진도\"}");
        }
        return "초안이에요\n" + AiStreamParser.DELIMITER + "\n"
                + "{\"title\":\"" + TITLE_PREFIX + "주간\",\"goalSummary\":null,"
                + "\"items\":[" + items + "]}";
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private Long userId() {
        if (userId != null) {
            return userId;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM users ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("users 테이블이 비어 있어 테스트할 수 없다");
            }
            userId = rs.getLong(1);
            return userId;
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }

    private long maxUsageLogId() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(usage_log_id), 0) FROM ai_usage_logs");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }

    private int countRows(String table, String where) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("검증 질의 실패", e);
        }
    }

    private void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static class RecordingSink implements AiTurnEventSink {
        final StringBuilder deltas = new StringBuilder();
        final AtomicReference<Long> startedFor = new AtomicReference<>();
        AiTurnCompletedPayload completed;
        ErrorCode errorCode;
        OfferAction offerAction;
        AiProposalResponse proposalReady;
        PlanDraftResponse periodPlanReady;

        @Override public void onStarted(Long requestMessageId) { startedFor.set(requestMessageId); }
        @Override public void onDelta(String text) { deltas.append(text); }
        @Override public void onOfferReady(OfferAction action) { this.offerAction = action; }
        @Override public void onProposalReady(AiProposalResponse proposal) { this.proposalReady = proposal; }
        @Override public void onPeriodPlanReady(PlanDraftResponse draft) { this.periodPlanReady = draft; }
        @Override public void onContextSuggestionsReady(List<ContextSuggestionResponse> suggestions) { }
        @Override public void onScheduleSuggestionsReady(List<ScheduleSuggestionResponse> suggestions) { }
        @Override public void onCompleted(AiTurnCompletedPayload payload) { this.completed = payload; }
        @Override public void onError(ErrorCode code) { this.errorCode = code; }
    }
}
