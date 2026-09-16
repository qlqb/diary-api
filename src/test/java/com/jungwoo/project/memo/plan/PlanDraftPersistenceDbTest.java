package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.dto.PlanRedraftRequest;
import com.jungwoo.project.memo.plan.selection.PlanSelectionFixture;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 초안 저장 구간의 실제 트랜잭션 경계와 경합 보호. 실제 Spring 빈으로 진입해 실제 로컬 memo DB에서 본다.
 *
 * <p>수정 전 코드에서는 createDraft·redraft가 같은 클래스의 persist를 직접 불러 @Transactional이 프록시를 거치지 못했다 —
 * 제안 저장(AiProposalPersistenceService)만 따로 커밋되고 계획 메타·요청 맥락·옛 초안 폐기는 각각 autocommit이었다. 그래서
 * 메타 저장이 실패하면 기간·전략이 없는 제안이 남았고(둘째 시나리오), 잠금(FOR UPDATE)도 저장 전체를 보호하지 못했다.
 *
 * <p>모델은 부르지 않는다. AiConsultationClient만 목으로 갈아끼우고, 필요하면 계획 호출을 래치로 붙잡아 두 요청을 겹친다.
 * 테스트 자체는 트랜잭션이 없다 — 상태 확인은 별도 커넥션(DataSource)으로 한다. 스키마가 레포에 없어 CI에서는
 * -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@TestPropertySource(properties = "plan.draft.generator=AI")
@SpringBootTest
class PlanDraftPersistenceDbTest {

    private static final String TITLE_PREFIX = "PDP-";

    @MockitoBean
    private AiConsultationClient aiConsultationClient;
    @MockitoSpyBean
    private PlanStrategyCodec strategyCodec;
    @Autowired
    private PlanDraftService planDraftService;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private DataSource dataSource;

    private Long userId;
    private long proposalWatermark;
    private long usageLogWatermark;
    /** 계획 호출을 붙잡아 두는 문. null이면 바로 답한다. */
    private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> reached = new AtomicReference<>();
    private final AtomicInteger planCalls = new AtomicInteger();
    private final AtomicBoolean txActiveDuringModelCall = new AtomicBoolean(false);
    private final AtomicReference<Boolean> txActiveDuringSave = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        proposalWatermark = maxId("ai_proposals", "proposal_id");
        usageLogWatermark = maxId("ai_usage_logs", "usage_log_id");
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any(), anyInt())).thenAnswer(inv -> {
            String system = inv.getArgument(0);
            if (PlanSelectionFixture.isSelection(system)) {
                return PlanSelectionFixture.structured(PlanSelectionFixture.emptySelection());
            }
            planCalls.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txActiveDuringModelCall.set(true);
            }
            CountDownLatch r = reached.get();
            if (r != null) {
                r.countDown();
            }
            CountDownLatch g = gate.get();
            if (g != null && !g.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 문이 열리지 않았다");
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(planJson())))));
        });
        doAnswer(inv -> {
            txActiveDuringSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(strategyCodec).toJson(any());
    }

    @AfterEach
    void cleanUp() throws Exception {
        gate.set(null);
        reached.set(null);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM execution_item_events WHERE execution_item_id IN "
                    + "(SELECT execution_item_id FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%')");
            exec(conn, "DELETE FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%'");
            exec(conn, "DELETE FROM plan_versions WHERE title LIKE '" + TITLE_PREFIX + "%'");
            exec(conn, "DELETE FROM ai_proposal_schedule_previews WHERE proposal_id > " + proposalWatermark
                    + " AND proposal_id IN (SELECT proposal_id FROM ai_proposals WHERE user_id = " + userId() + ")");
            exec(conn, "DELETE FROM ai_proposal_items WHERE proposal_id > " + proposalWatermark
                    + " AND proposal_id IN (SELECT proposal_id FROM ai_proposals WHERE user_id = " + userId() + ")");
            exec(conn, "DELETE FROM ai_proposals WHERE proposal_id > " + proposalWatermark + " AND user_id = " + userId());
            exec(conn, "DELETE FROM ai_usage_logs WHERE usage_log_id > " + usageLogWatermark + " AND user_id = " + userId());
        }
    }

    // ===== 경계 =====

    @Test
    void modelCallRunsOutsideAnyTransaction_andTheSaveRunsInsideOne() {
        PlanDraftResponse draft = planDraftService.createDraft(userId(), request("k-boundary"));

        assertThat(draft.getProposalId()).isNotNull();
        assertThat(txActiveDuringModelCall.get()).as("모델 호출 중에는 쓰기 트랜잭션이 없다").isFalse();
        assertThat(txActiveDuringSave.get()).as("저장 구간(계획 메타 직렬화 시점)에는 트랜잭션이 있다").isTrue();
        AiProposal stored = aiProposalMapper.findByIdAndUserId(draft.getProposalId(), userId());
        assertThat(stored.getPlanStartDate()).isNotNull();
        assertThat(stored.getPlanRequestJson()).contains("\"requestKey\":\"k-boundary\"");
    }

    @Test
    void whenMetadataSaveFails_noHalfSavedProposalRemains() {
        doAnswer(inv -> {
            throw new IllegalStateException("의도적 실패: 전략 직렬화");
        }).when(strategyCodec).toJson(any());
        int before = countRows("ai_proposals", "user_id = " + userId() + " AND proposal_id > " + proposalWatermark);

        assertThatThrownBy(() -> planDraftService.createDraft(userId(), request("k-fail")))
                .isInstanceOf(IllegalStateException.class);

        // 제안 저장은 메타 저장보다 먼저 일어났지만 같은 트랜잭션이라 함께 되돌아갔다.
        assertThat(countRows("ai_proposals", "user_id = " + userId() + " AND proposal_id > " + proposalWatermark))
                .isEqualTo(before);
        assertThat(countRows("ai_proposal_items", "proposal_id > " + proposalWatermark
                + " AND proposal_id IN (SELECT proposal_id FROM ai_proposals WHERE user_id = " + userId() + ")")).isZero();
        // 같은 키로 다시 시도하면 새로 만든다 — 반쯤 저장된 결과를 돌려주지 않는다.
        assertThat(planDraftService.progressOf(userId(), "k-fail")).isPresent();
    }

    // ===== 경합 =====

    @Test
    void redraftRacingWithConfirm_doesNotSaveTheLateResult() throws Exception {
        PlanDraftResponse original = planDraftService.createDraft(userId(), request("k-orig-1"));
        CountDownLatch g = new CountDownLatch(1);
        CountDownLatch r = new CountDownLatch(1);
        gate.set(g);
        reached.set(r);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> late = pool.submit(() -> {
                try {
                    return planDraftService.redraft(userId(), original.getProposalId(), redraft("k-redraft-1"));
                } catch (RuntimeException e) {
                    return e;
                }
            });
            assertThat(r.await(20, TimeUnit.SECONDS)).as("다시 만들기가 모델 호출에 들어갔다").isTrue();

            // 모델을 기다리는 사이 사용자가 원본을 확정했다.
            PlanVersion plan = planConfirmService.confirm(userId(), original.getProposalId(),
                    PlanConfirmRequest.builder().title(TITLE_PREFIX + "경합 확정").build());
            assertThat(plan.getPlanVersionId()).isNotNull();

            g.countDown();
            Object result = late.get(30, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(ConflictException.class);
            assertThat(((ConflictException) result).getErrorCode()).isEqualTo(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        } finally {
            pool.shutdownNow();
        }
        // 늦은 결과는 저장되지 않았다: 원본을 대체하는 PROPOSED 초안이 없다.
        assertThat(countRows("ai_proposals", "user_id = " + userId() + " AND proposal_id > " + proposalWatermark
                + " AND status = 'PROPOSED'")).isZero();
        assertThat(countRows("ai_proposals", "proposal_id = " + original.getProposalId() + " AND status <> 'PROPOSED'"))
                .isEqualTo(1);
    }

    @Test
    void twoRedraftsOfTheSameOriginal_yieldExactlyOneReplacement_andTheSameKeyRetryReturnsIt() throws Exception {
        PlanDraftResponse original = planDraftService.createDraft(userId(), request("k-orig-2"));
        CountDownLatch g = new CountDownLatch(1);
        CountDownLatch r = new CountDownLatch(2);
        gate.set(g);
        reached.set(r);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String key : List.of("k-redraft-a", "k-redraft-b")) {
                futures.add(pool.submit(() -> {
                    try {
                        return planDraftService.redraft(userId(), original.getProposalId(), redraft(key));
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            assertThat(r.await(20, TimeUnit.SECONDS)).as("두 요청 모두 모델 호출에 들어갔다").isTrue();
            g.countDown();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        List<PlanDraftResponse> winners = results.stream().filter(PlanDraftResponse.class::isInstance)
                .map(PlanDraftResponse.class::cast).toList();
        assertThat(winners).as("유효한 대체 결과는 하나").hasSize(1);
        assertThat(results.stream().filter(ConflictException.class::isInstance)).hasSize(1);
        assertThat(countRows("ai_proposals", "user_id = " + userId() + " AND proposal_id > " + proposalWatermark
                + " AND status = 'PROPOSED'")).isEqualTo(1);
        assertThat(aiProposalMapper.findByIdAndUserId(original.getProposalId(), userId()).getStatus())
                .isEqualTo(AiProposalStatus.DISMISSED);
        PlanDraftResponse winner = winners.get(0);
        assertThat(winner.getPreviousDraft().getProposalId()).isEqualTo(original.getProposalId());

        // 이긴 요청 키로 다시 시도(늦은 응답·새로고침): 원본이 이미 DISMISSED여도 그 결과를 돌려주고 모델을 부르지 않는다.
        gate.set(null);
        String winningKey = aiProposalMapper.findByIdAndUserId(winner.getProposalId(), userId()).getPlanRequestJson()
                .contains("k-redraft-a") ? "k-redraft-a" : "k-redraft-b";
        int callsBefore = planCalls.get();
        PlanDraftResponse again = planDraftService.redraft(userId(), original.getProposalId(), redraft(winningKey));
        assertThat(again.getProposalId()).isEqualTo(winner.getProposalId());
        assertThat(planCalls.get()).isEqualTo(callsBefore);

        // 진 요청 키로 다시 시도하면 원본이 이미 대체됐다고 답한다 — 두 번째 대체를 만들지 않는다.
        String losingKey = winningKey.equals("k-redraft-a") ? "k-redraft-b" : "k-redraft-a";
        assertThatThrownBy(() -> planDraftService.redraft(userId(), original.getProposalId(), redraft(losingKey)))
                .isInstanceOf(ConflictException.class);
        assertThat(planCalls.get()).isEqualTo(callsBefore);
    }

    @Test
    void requestKeyOfAnotherTarget_isNotReusedForThisOne() {
        PlanDraftResponse a = planDraftService.createDraft(userId(), request("k-shared"));
        PlanDraftResponse b = planDraftService.createDraft(userId(), request("k-other"));

        // b를 다시 만들면서 a의 새 요청 키를 대면, a의 결과를 돌려주지도 b를 덮지도 않는다.
        assertThatThrownBy(() -> planDraftService.redraft(userId(), b.getProposalId(), redraft("k-shared")))
                .isInstanceOf(ConflictException.class);
        assertThat(aiProposalMapper.findByIdAndUserId(a.getProposalId(), userId()).getStatus()).isEqualTo(AiProposalStatus.PROPOSED);
        assertThat(aiProposalMapper.findByIdAndUserId(b.getProposalId(), userId()).getStatus()).isEqualTo(AiProposalStatus.PROPOSED);
    }

    @Test
    void loadingAReplacedDraft_returnsTheLatestOpenReplacement_andItemsRegenerateGoesThroughRedraft() {
        PlanDraftResponse original = planDraftService.createDraft(userId(), request("k-orig-3"));
        int callsBefore = planCalls.get();

        // 옛 조각 재생성 엔드포인트로 들어와도 요청이 저장된 초안은 같은 조건으로 다시 만들기(공통 경로)로 간다.
        PlanDraftResponse replaced = planDraftService.regenerateItems(userId(), original.getProposalId());
        assertThat(replaced.getProposalId()).isNotEqualTo(original.getProposalId());
        assertThat(replaced.getPreviousDraft().getProposalId()).isEqualTo(original.getProposalId());
        assertThat(replaced.getRequestContext().isRedraftable()).isTrue();
        assertThat(planCalls.get()).isEqualTo(callsBefore + 1);
        assertThat(aiProposalMapper.findByIdAndUserId(original.getProposalId(), userId()).getStatus())
                .isEqualTo(AiProposalStatus.DISMISSED);

        // 옛 id로 다시 읽으면(상담 메시지·세션이 들고 있는 값) 지금 열린 대체 초안이 온다.
        PlanDraftResponse loaded = planDraftService.loadDraft(userId(), original.getProposalId());
        assertThat(loaded.getProposalId()).isEqualTo(replaced.getProposalId());
        assertThat(loaded.getProposal().getStatus()).isEqualTo(AiProposalStatus.PROPOSED);
    }

    // ===== fixture =====

    private PlanDraftRequest request(String key) {
        LocalDate start = LocalDate.now().plusDays(7);
        return PlanDraftRequest.builder().startDate(start).endDate(start.plusDays(6)).requestKey(key).build();
    }

    private static PlanRedraftRequest redraft(String key) {
        return PlanRedraftRequest.builder().requestKey(key).build();
    }

    private static String planJson() {
        return "초안이에요\n" + AiStreamParser.DELIMITER + "\n"
                + "{\"title\":\"" + TITLE_PREFIX + "주간\",\"goalSummary\":\"목표\",\"strategy\":{\"goal\":\"목표\"},"
                + "\"items\":[{\"title\":\"" + TITLE_PREFIX + "항목1\",\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":30,"
                + "\"priority\":\"SHOULD\",\"courseId\":null,\"scheduledDate\":null,\"reason\":\"이유\",\"refIds\":[]},"
                + "{\"title\":\"" + TITLE_PREFIX + "항목2\",\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":45,"
                + "\"priority\":\"SHOULD\",\"courseId\":null,\"scheduledDate\":null,\"reason\":\"이유\",\"refIds\":[]}]}";
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

    private long maxId(String table, String column) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(" + column + "), 0) FROM " + table);
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
}
