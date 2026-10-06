package com.jungwoo.project.memo.plan.provenance;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.PlanConfirmService;
import com.jungwoo.project.memo.plan.PlanVersionMapper;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.service.SchedulePreviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 출처 기록이 저장·확정·적용·조회를 지나 살아남는지 실제 로컬 MariaDB(memo)에 대고 본다.
 *
 * <p>단위 테스트로는 증명할 수 없는 것들이 대상이다 — 확정이 스냅샷을 계획으로 옮기는지,
 * 적용된 조각에서 원래 회차로 되짚어지는지(조정 제안이 끼어든 뒤에도), 원본 행을 실제로
 * UPDATE한 뒤에도 스냅샷이 그대로인지, 미리보기 이후 생긴 약속을 확정이 잡아내는지, 그리고
 * <b>적용이 서버 소유 스냅샷을 건드리지 않는지</b>.
 *
 * <p>★ 실사용 계정을 쓰지 않는다. 시작할 때 전용 사용자 행을 만들고, 끝날 때 그 사용자의
 * 행만 지운다 — 제목 패턴으로 남의 행까지 훑는 정리는 하지 않는다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 * 이 스위트는 docs/sql/2026-09-10-plan-provenance.sql이 적용돼 있어야 돈다.
 */
@SpringBootTest
class PlanProvenanceIntegrationTest {

    /**
     * 이 스위트만 쓰는 사용자. 매번 새로 만들고 끝나면 지운다.
     *
     * <p>id를 직접 정하지 않는다 — users는 다른 표들이 FK로 가리키고 있어 큰 번호를 직접
     * 넣으면 auto_increment가 그 뒤로 옮겨가, 다음 실제 가입자가 그 번호대를 받는다.
     */
    private static Long TEST_USER_ID;
    private static final String EMAIL_PREFIX = "prv-test-";
    private static final String TITLE_PREFIX = "PRV-";

    @Autowired
    private AiProposalService aiProposalService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private AiProposalItemMapper aiProposalItemMapper;
    @Autowired
    private PlanProvenanceService planProvenanceService;
    @Autowired
    private PlanProvenanceCodec codec;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private PlanVersionMapper planVersionMapper;
    @Autowired
    private SchedulePreviewService schedulePreviewService;
    @Autowired
    private CommitmentMapper commitmentMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createTestUser() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (email, password_hash, nickname, role, status) "
                             + "VALUES (?, 'not-a-real-hash', 'prv-test', 'USER', 'ACTIVE')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, EMAIL_PREFIX + System.nanoTime() + "@example.invalid");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                TEST_USER_ID = keys.getLong(1);
            }
        }
    }

    /**
     * 이 사용자가 만든 행만 지운다. FK 순서: 사건 → 조각 → 계획 → 미리보기 → 제안 항목 →
     * 제안 → 약속 → 사용자.
     */
    @AfterEach
    void cleanUp() throws Exception {
        if (TEST_USER_ID == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM execution_item_events WHERE user_id = ?",
                    "DELETE FROM execution_items WHERE user_id = ?",
                    "DELETE FROM plan_versions WHERE user_id = ?",
                    "DELETE FROM ai_proposal_schedule_previews WHERE user_id = ?",
                    "DELETE FROM ai_proposal_items WHERE user_id = ?",
                    "DELETE FROM ai_proposals WHERE user_id = ?",
                    "DELETE FROM one_off_commitments WHERE user_id = ?",
                    "DELETE FROM users WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, TEST_USER_ID);
                    ps.executeUpdate();
                }
            }
        }
        TEST_USER_ID = null;
    }

    // ===== 초안에서 조회 =====

    @Test
    void draftProvenance_returnsWhatWasProvided_andPerItemEvidence() {
        Fixture fixture = givenProposalWithProvenance(2);

        PlanProvenanceResponse response = planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId());

        assertThat(response.recorded()).isTrue();
        assertThat(response.generationId()).isEqualTo(fixture.generationId());
        assertThat(response.providedSources()).extracting(PlanProvenanceResponse.SourceView::refId)
                .containsExactly("s1", "s2");
        assertThat(response.serverCalculations()).hasSize(1);

        assertThat(response.items()).hasSize(2);
        PlanProvenanceResponse.ItemView first = response.items().get(0);
        assertThat(first.recorded()).isTrue();
        assertThat(first.generationId()).isEqualTo(fixture.generationId());
        assertThat(first.refIds()).containsExactly("s1");
        assertThat(first.evidenceStatus()).isEqualTo(EvidenceStatus.CURRENT);
        assertThat(first.aiEstimates()).contains("예상 소요 시간 40분");
        assertThat(first.afterApplyChanges()).isEmpty();
    }

    /**
     * 원본 행을 <b>실제로 UPDATE</b>한 뒤에도 스냅샷은 그때 값이다. 안 그러면 과거 제안이
     * 현재 값으로 정당화된다. 링크는 지금 원본을 가리키므로 여전히 열린다.
     */
    @Test
    void theSnapshotKeepsTheValueAsItWas_evenAfterTheSourceRowIsActuallyUpdated() throws Exception {
        Commitment shift = insertCommitment("근무", LocalDate.now().plusDays(8).atTime(18, 0),
                LocalDate.now().plusDays(8).atTime(23, 0));
        Fixture fixture = givenProposalWithProvenance(1, shift.getCommitmentId());

        PlanProvenanceResponse before = planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId());
        PlanProvenanceResponse.SourceView recorded = before.providedSources().get(0);
        assertThat(recorded.link().available()).isTrue();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE one_off_commitments SET title = '근무(변경)', start_at = ?, end_at = ? "
                             + "WHERE commitment_id = ? AND user_id = ?")) {
            ps.setObject(1, LocalDate.now().plusDays(9).atTime(9, 0));
            ps.setObject(2, LocalDate.now().plusDays(9).atTime(12, 0));
            ps.setLong(3, shift.getCommitmentId());
            ps.setLong(4, TEST_USER_ID);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        PlanProvenanceResponse after = planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId());
        PlanProvenanceResponse.SourceView again = after.providedSources().get(0);
        assertThat(again.promptLine()).isEqualTo(recorded.promptLine());
        assertThat(again.providedValue()).isEqualTo(recorded.providedValue());
        assertThat(again.providedValue()).containsEntry("label", "근무");
        // 원본은 남아 있으므로 지금 원본으로 가는 링크는 열린다 — 당시 값과는 별개다.
        assertThat(again.link().available()).isTrue();
        assertThat(again.link().targetId()).isEqualTo(shift.getCommitmentId());
    }

    /** 지워졌거나 볼 수 없는 원본은 링크를 끄고 이유를 말한다 — 깨진 링크를 만들지 않는다. */
    @Test
    void aSourceRowThatNoLongerExists_getsADisabledLinkWithAReason() {
        Fixture fixture = givenProposalWithProvenance(1);

        PlanProvenanceResponse response = planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId());
        PlanProvenanceResponse.SourceView commitment = response.providedSources().stream()
                .filter(s -> s.sourceType() == ProvenanceSourceType.COMMITMENT)
                .findFirst().orElseThrow();

        // 픽스처의 commitment id는 실제로 없는 값이다.
        assertThat(commitment.link().available()).isFalse();
        assertThat(commitment.link().unavailableReason()).isNotBlank();
        // 그래도 당시 값은 그대로 보인다.
        assertThat(commitment.promptLine()).contains("근무");
    }

    // ===== 접근 제어 =====

    @Test
    void anotherUsersProposal_isNotReadable() {
        Fixture fixture = givenProposalWithProvenance(1);
        Long otherUserId = TEST_USER_ID + 1;

        assertThatThrownBy(() -> planProvenanceService.forProposal(otherUserId, fixture.proposalId()))
                .isInstanceOf(NotFoundException.class);
    }

    // ===== 레거시 =====

    @Test
    void aProposalWithoutProvenance_readsAsNotRecorded_insteadOfFailing() {
        Long proposalId = givenPlanProposal(2, null, null);

        PlanProvenanceResponse response = planProvenanceService.forProposal(TEST_USER_ID, proposalId);

        assertThat(response.recorded()).isFalse();
        assertThat(response.providedSources()).isEmpty();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.recorded()).isFalse();
            assertThat(item.refIds()).isEmpty();
        });
    }

    // ===== 확정 이후 =====

    @Test
    void confirm_carriesTheSnapshotToThePlan_andAppliedItemsPointBackToTheSameGeneration() {
        Fixture fixture = givenProposalWithProvenance(2);

        PlanVersion plan = planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "확정").build());

        PlanVersion stored = planVersionMapper.findByIdAndUserId(plan.getPlanVersionId(), TEST_USER_ID);
        assertThat(stored.getProvenanceJson()).as("확정이 스냅샷을 계획으로 옮긴다").isNotBlank();
        assertThat(codec.fromJson(stored.getProvenanceJson()).generationId())
                .isEqualTo(fixture.generationId());

        Long executionItemId = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID).get(0).getCreatedItemId();
        assertThat(executionItemId).isNotNull();

        PlanProvenanceResponse response = planProvenanceService.forExecutionItem(TEST_USER_ID, executionItemId);
        assertThat(response.recorded()).isTrue();
        assertThat(response.generationId()).isEqualTo(fixture.generationId());
        assertThat(response.planVersionId()).isEqualTo(plan.getPlanVersionId());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).executionItemId()).isEqualTo(executionItemId);
        assertThat(response.items().get(0).afterApplyChanges()).isEmpty();
    }

    /**
     * AI 조정 제안(줄이기)을 적용하면 같은 created_item_id를 가리키는 행이 하나 더 생긴다.
     * 그 행은 이 조각을 만든 회차가 아니다 — 근거 조회는 최근 행이 아니라 CREATE 원본을 골라야
     * 하고, 조정이 있었다는 사실은 "적용한 뒤 바뀜"으로 따로 말한다.
     */
    @Test
    void afterAnAiAdjustmentIsApplied_theCreateOriginIsStillFound_andTheChangeIsReported() {
        Fixture fixture = givenProposalWithProvenance(1);
        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "확정 후 조정").build());
        Long executionItemId = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID).get(0).getCreatedItemId();

        AiProposalResponse adjustment = aiProposalService.createFromItems(
                TEST_USER_ID, null, null, List.of(),
                List.of(new ProposalAdjustment(executionItemId, ProposalOperation.REDUCE, 20, null,
                        null, null, null, "시간이 모자라서")),
                LocalDate.now(), List.of());
        aiProposalService.apply(adjustment.getProposalId(), TEST_USER_ID,
                AiProposalApplyRequest.builder().build());

        // 같은 조각을 가리키는 제안 항목이 둘이다 — 최근 것은 조정 행이다.
        assertThat(aiProposalItemMapper.findByCreatedItemIdAndUserId(executionItemId, TEST_USER_ID)).hasSize(2);

        PlanProvenanceResponse response = planProvenanceService.forExecutionItem(TEST_USER_ID, executionItemId);
        assertThat(response.recorded()).as("조정 행이 아니라 CREATE 원본을 되짚는다").isTrue();
        assertThat(response.generationId()).isEqualTo(fixture.generationId());
        assertThat(response.items()).hasSize(1);
        PlanProvenanceResponse.ItemView item = response.items().get(0);
        assertThat(item.recorded()).isTrue();
        assertThat(item.refIds()).containsExactly("s1");
        assertThat(item.afterApplyChanges()).anySatisfy(text -> assertThat(text).contains("분량을 줄였어요"));
    }

    /**
     * 적용은 서버 소유 스냅샷을 건드리지 않는다.
     *
     * <p>클라이언트가 보내는 것은 항목의 값(제목·분량·날짜)뿐이다. 요청 DTO에 스냅샷 필드가
     * 없다는 것이 1차 방어선이고, 이 테스트는 실제로 저장된 값이 그대로인지를 본다.
     */
    @Test
    void applyingWithUserEdits_neverRewritesTheServerOwnedSnapshot() {
        Fixture fixture = givenProposalWithProvenance(1);
        String before = aiProposalMapper.findByIdAndUserId(fixture.proposalId(), TEST_USER_ID)
                .getPlanProvenanceJson();

        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "고쳐서 확정")
                .editedItems(List.of(editedTitle(items.get(0).getProposalItemId(), TITLE_PREFIX + "내가 고친 제목")))
                .build());

        AiProposal after = aiProposalMapper.findByIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        assertThat(after.getPlanProvenanceJson()).isEqualTo(before);
    }

    /** 내용을 고치면 근거는 "수정 전 제안의 것"이 된다. 지우지 않고 상태만 바꾼다. */
    @Test
    void editingTheContentAtConfirmTime_marksTheEvidenceAsPreEdit() {
        Fixture fixture = givenProposalWithProvenance(1);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        Long proposalItemId = items.get(0).getProposalItemId();

        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "내용 수정")
                .editedItems(List.of(editedTitle(proposalItemId, TITLE_PREFIX + "다른 제목")))
                .build());

        PlanProvenanceResponse response = planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId());
        PlanProvenanceResponse.ItemView item = response.items().get(0);

        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.EDITED_BY_USER);
        assertThat(item.staleReasons()).isNotEmpty();
        // 근거 자체는 그대로다 — 고치기 전 근거가 남아야 무엇이 왜 바뀌었는지 읽을 수 있다.
        assertThat(item.refIds()).containsExactly("s1");
    }

    /**
     * 날짜·시각을 직접 바꾸면 더 보수적으로 재검토 대상이 된다.
     *
     * <p>그때의 배치 계산은 이 시각에 대한 것이 아니다. 바꾸기 전 결과를 현재 배치의 근거로
     * 남겨 두면, 사용자가 자기가 정한 시각을 "AI가 검토한 시각"으로 읽는다.
     */
    @Test
    void changingTheDateAtConfirmTime_marksTheEvidenceForReview() {
        Fixture fixture = givenProposalWithProvenance(1);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);

        AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(items.get(0).getProposalItemId());
        edit.setPlacementType(PlacementType.DATE_ONLY);
        edit.setScheduledDate(LocalDate.now().plusDays(8));

        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "날짜 수정")
                .editedItems(List.of(edit))
                .build());

        PlanProvenanceResponse.ItemView item =
                planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId()).items().get(0);

        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.NEEDS_REVIEW);
        assertThat(item.staleReasons()).isNotEmpty();
        assertThat(item.refIds()).as("근거 자체는 지우지 않는다").containsExactly("s1");
    }

    /**
     * 서버 미리보기가 정한 시각을 그대로 확정한 것은 <b>사용자 수정이 아니다.</b>
     *
     * <p>확정 요청은 배치 미리보기의 시각을 그대로 싣는다. 그것까지 수정으로 세면 확정한
     * 모든 항목에 "다시 볼 근거"가 붙어 표식이 뜻을 잃는다 — 실제 화면에서 그렇게 나왔다.
     *
     * <p>픽스처는 배치 결과가 <b>반드시</b> 나오게 만든다: 계획은 내일부터 7일(미리보기 기본
     * 창 안), 항목은 30분 하나, 이 사용자의 막힌 시간은 없다. 배치가 안 나오면 이 테스트는
     * 실패다 — 건너뛰지 않는다.
     */
    @Test
    void confirmingTheServersOwnPreviewPlacement_isNotCountedAsAUserEdit() {
        Fixture fixture = givenPlaceableProposal();

        // 미리보기를 실제로 계산해 저장한다(OpenAI 호출 없음).
        SchedulePreviewResponse preview = schedulePreviewService.computePreview(
                TEST_USER_ID, fixture.proposalId(), new SchedulePreviewRequest());
        assertThat(preview.getPlacedItems()).as("내일부터의 빈 시간에 30분 항목은 배치돼야 한다").hasSize(1);

        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "미리보기 그대로 확정")
                .editedItems(previewAsEdits(preview))
                .build());

        PlanProvenanceResponse.ItemView item =
                planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId()).items().get(0);
        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.CURRENT);
        assertThat(item.staleReasons()).isEmpty();
    }

    /** 같은 픽스처에서 미리보기 시각을 사용자가 바꾸면 재검토 대상이다 — 위와 짝이 되는 비교. */
    @Test
    void changingThePreviewPlacement_isCountedAsAUserEdit() {
        Fixture fixture = givenPlaceableProposal();
        SchedulePreviewResponse preview = schedulePreviewService.computePreview(
                TEST_USER_ID, fixture.proposalId(), new SchedulePreviewRequest());
        assertThat(preview.getPlacedItems()).hasSize(1);

        List<AiProposalApplyRequest.EditedProposalItem> edits = previewAsEdits(preview);
        AiProposalApplyRequest.EditedProposalItem edit = edits.get(0);
        // 미리보기보다 한 시간 뒤로 옮긴다(같은 날, 여전히 미래).
        edit.setScheduledStartAt(edit.getScheduledStartAt().plusHours(1));
        edit.setScheduledEndAt(edit.getScheduledEndAt().plusHours(1));

        planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "미리보기 시각 수정")
                .editedItems(edits)
                .build());

        PlanProvenanceResponse.ItemView item =
                planProvenanceService.forProposal(TEST_USER_ID, fixture.proposalId()).items().get(0);
        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.NEEDS_REVIEW);
        assertThat(item.staleReasons()).isNotEmpty();
    }

    // ===== 미리보기 이후 바뀐 일정 =====

    /**
     * 미리보기가 정한 시각에 그 사이 근무가 생겼으면 확정 전체를 거절한다. 겹친 항목만 빼고
     * 확정하면 사용자가 승인한 계획과 저장된 계획이 조용히 달라진다.
     */
    @Test
    void aCommitmentAddedAfterThePreview_rejectsTheWholeConfirm() {
        Fixture fixture = givenProposalWithProvenance(2);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        LocalDateTime slot = LocalDate.now().plusDays(8).atTime(19, 0);

        // "미리보기"가 정한 시각 두 개. 첫 항목만 나중에 생긴 근무와 겹친다.
        List<AiProposalApplyRequest.EditedProposalItem> edits = List.of(
                timeFixed(items.get(0).getProposalItemId(), slot, slot.plusMinutes(40)),
                timeFixed(items.get(1).getProposalItemId(), slot.plusHours(3), slot.plusHours(3).plusMinutes(40)));
        insertCommitment("근무", slot.minusMinutes(30), slot.plusMinutes(30));

        assertThatThrownBy(() -> planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "겹침").editedItems(edits).build()))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT));

        // 겹치지 않은 둘째 항목도 만들어지지 않았다 — 전체 거절이다.
        assertThat(countExecutionItems()).isZero();
        assertThat(aiProposalItemMapper.findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID))
                .allSatisfy(item -> assertThat(item.getCreatedItemId()).isNull());
    }

    @Test
    void withoutAnOverlap_theSameTimedConfirmSucceeds() {
        Fixture fixture = givenProposalWithProvenance(1);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        LocalDateTime slot = LocalDate.now().plusDays(8).atTime(19, 0);
        insertCommitment("근무", slot.plusHours(2), slot.plusHours(5));

        PlanVersion plan = planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "겹침 없음")
                        .editedItems(List.of(timeFixed(items.get(0).getProposalItemId(), slot, slot.plusMinutes(40))))
                        .build());

        assertThat(plan.getPlanVersionId()).isNotNull();
        assertThat(countExecutionItems()).isEqualTo(1);
    }

    /**
     * 약속 INSERT와 확정이 <b>겹쳐서</b> 도는 경우. 확정의 잠금 조회는 아직 커밋되지 않은
     * 겹치는 INSERT가 끝날 때까지 기다리고, 커밋된 뒤에 그것을 보고 거절한다.
     *
     * <p>순차 테스트(먼저 넣고 확정)로는 이걸 증명할 수 없다. 막으려는 것은 "검사할 때는
     * 없었는데 커밋 직후에 생긴" 창이고, 그 창은 두 트랜잭션이 겹칠 때만 열린다. 순서는
     * 래치로만 제어한다 — sleep으로 맞추면 통과가 우연이 된다.
     */
    @Test
    void aCommitmentCommittedWhileTheConfirmIsRunning_isStillSeenAndRejected() throws Exception {
        Fixture fixture = givenProposalWithProvenance(1);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), TEST_USER_ID);
        LocalDateTime slot = LocalDate.now().plusDays(8).atTime(19, 0);
        PlanConfirmRequest request = PlanConfirmRequest.builder().title(TITLE_PREFIX + "경쟁")
                .editedItems(List.of(timeFixed(items.get(0).getProposalItemId(), slot, slot.plusMinutes(40))))
                .build();

        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        AtomicReference<Throwable> confirmOutcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> insert = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                insertCommitment("근무", slot.minusMinutes(30), slot.plusMinutes(30));
                inserted.countDown();
                try {
                    // 커밋하지 않은 채 들고 있는다 — 확정의 잠금 조회가 여기서 기다려야 한다.
                    releaseInsert.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> confirm = pool.submit(() -> {
                try {
                    planConfirmService.confirm(TEST_USER_ID, fixture.proposalId(), request);
                } catch (Throwable t) {
                    confirmOutcome.set(t);
                }
            });

            // 잠금이 있다면 확정은 INSERT가 커밋될 때까지 끝나지 않는다.
            assertThatThrownBy(() -> confirm.get(700, TimeUnit.MILLISECONDS))
                    .as("확정이 커밋되지 않은 약속을 기다린다")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseInsert.countDown();
            insert.get(10, TimeUnit.SECONDS);
            confirm.get(10, TimeUnit.SECONDS);
        } finally {
            releaseInsert.countDown();
            pool.shutdownNow();
        }

        assertThat(confirmOutcome.get()).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) confirmOutcome.get()).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT);
        assertThat(countExecutionItems()).isZero();
    }

    // ===== 회차가 섞이지 않는다 =====

    @Test
    void twoGenerations_keepTheirOwnSourcesAndEvidence() {
        Fixture first = givenProposalWithProvenance(1);
        Fixture second = givenProposalWithProvenance(1);

        assertThat(first.generationId()).isNotEqualTo(second.generationId());

        PlanProvenanceResponse firstResponse = planProvenanceService.forProposal(TEST_USER_ID, first.proposalId());
        PlanProvenanceResponse secondResponse = planProvenanceService.forProposal(TEST_USER_ID, second.proposalId());

        assertThat(firstResponse.items().get(0).generationId()).isEqualTo(first.generationId());
        assertThat(secondResponse.items().get(0).generationId()).isEqualTo(second.generationId());
        // 앞 회차를 다시 만들었다고 지우지 않는다 — 옛 근거는 그대로 조회된다.
        assertThat(firstResponse.recorded()).isTrue();
    }

    // ===== fixture =====

    private record Fixture(Long proposalId, String generationId) {
    }

    private Fixture givenProposalWithProvenance(int itemCount) {
        return givenProposalWithProvenance(itemCount, 999_000_001L);
    }

    /**
     * 미리보기 기본 창(오늘부터 7일) 안에 들어오는 계획: 내일부터 7일, 30분 항목 하나. 이 사용자는
     * 막힌 시간이 없으므로 기본 시간대(09~23시)에서 반드시 배치된다.
     */
    private Fixture givenPlaceableProposal() {
        String generationId = "gen-test-" + System.nanoTime();
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusDays(6);
        List<ProposalItem> items = List.of(new ProposalItem(TITLE_PREFIX + "배치 가능 항목", "이유", 30, "SHOULD",
                PlacementType.UNSCHEDULED, null, null, start, end, null, null, null));
        List<String> evidence = List.of(codec.toJson(PlanItemEvidence.of(
                generationId, List.of("s1"), "이유", List.of("예상 소요 시간 30분"), List.of(), 0)));
        AiProposalResponse proposal = aiProposalService.createFromItems(
                TEST_USER_ID, null, null, items, List.of(), start, List.of(), 30, evidence);
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), TEST_USER_ID, start, end,
                PlanIntensity.FOCUSED, 1080, null,
                codec.toJson(provenanceOf(generationId, start, end, 999_000_001L)));
        return new Fixture(proposal.getProposalId(), generationId);
    }

    /** @param commitmentId 스냅샷의 s1이 가리킬 약속. 없는 id를 주면 "지워진 원본"을 재현한다 */
    private Fixture givenProposalWithProvenance(int itemCount, Long commitmentId) {
        String generationId = "gen-test-" + System.nanoTime();
        Long proposalId = givenPlanProposal(itemCount, generationId, commitmentId);
        return new Fixture(proposalId, generationId);
    }

    /**
     * 계획 경로로 만들어진 제안 하나. generationId가 null이면 출처 없이 만든다(레거시 재현).
     *
     * <p>모델을 부르지 않고 서버가 만드는 모양 그대로 넣는다 — 여기서 보려는 것은 저장·확정·
     * 조회 경로이지 프롬프트 생성이 아니다(그쪽은 PlanProvenanceCaptureTest가 본다).
     */
    private Long givenPlanProposal(int itemCount, String generationId, Long commitmentId) {
        LocalDate start = LocalDate.now().plusDays(7);
        LocalDate end = start.plusDays(6);

        List<ProposalItem> items = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new ProposalItem(TITLE_PREFIX + "항목" + i, "이유 " + i, 40, "SHOULD",
                    PlacementType.UNSCHEDULED, null, null, start, end, null, null, null));
            evidence.add(generationId == null ? null : codec.toJson(PlanItemEvidence.of(
                    generationId, List.of("s1"), "이유 " + i,
                    List.of("예상 소요 시간 40분"), List.of(), 0)));
        }

        AiProposalResponse proposal = aiProposalService.createFromItems(
                TEST_USER_ID, null, null, items, List.of(), start, List.of(), 30,
                generationId == null ? List.of() : evidence);

        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), TEST_USER_ID, start, end,
                PlanIntensity.FOCUSED, 1080, null,
                generationId == null ? null : codec.toJson(provenanceOf(generationId, start, end, commitmentId)));
        return proposal.getProposalId();
    }

    /** 서버가 만드는 모양 그대로의 스냅샷. */
    private PlanProvenance provenanceOf(String generationId, LocalDate start, LocalDate end, Long commitmentId) {
        return new PlanProvenance(PlanProvenance.SCHEMA_VERSION, generationId,
                LocalDateTime.now(), "Asia/Seoul", start, end, "AI", "test-model",
                List.of(
                        new ProvidedSource("s1", ProvenanceSourceType.COMMITMENT, commitmentId, null, null,
                                ProvenanceRepresentation.SELECTED_FIELDS,
                                Map.of("label", "근무"), "8/24 월 18:00~23:00 근무 [s1]"),
                        new ProvidedSource("s2", ProvenanceSourceType.TURN_INPUT, null, null, null,
                                ProvenanceRepresentation.EXCERPT,
                                Map.of("field", "instruction", "text", "영어는 빼줘"), "영어는 빼줘 [s2]")),
                List.of(new ServerCalculation("calc-1",
                        ServerCalculation.ServerCalculationKind.AVAILABILITY_ESTIMATE, true,
                        List.of("s1"), ServerCalculation.InputLineage.PARTIAL, "기본 창은 서버 상수다",
                        Map.of("availableMinutes", 925))));
    }

    private Commitment insertCommitment(String title, LocalDateTime startAt, LocalDateTime endAt) {
        Commitment commitment = Commitment.builder()
                .userId(TEST_USER_ID).title(title).startAt(startAt).endAt(endAt)
                .sourceType(CommitmentSourceType.MANUAL).build();
        commitmentMapper.insert(commitment);
        return commitment;
    }

    private static AiProposalApplyRequest.EditedProposalItem timeFixed(Long proposalItemId,
                                                                        LocalDateTime start, LocalDateTime end) {
        AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(proposalItemId);
        edit.setPlacementType(PlacementType.TIME_FIXED);
        edit.setScheduledDate(start.toLocalDate());
        edit.setScheduledStartAt(start);
        edit.setScheduledEndAt(end);
        return edit;
    }

    private static List<AiProposalApplyRequest.EditedProposalItem> previewAsEdits(SchedulePreviewResponse preview) {
        List<AiProposalApplyRequest.EditedProposalItem> edits = new ArrayList<>();
        for (com.jungwoo.project.memo.scheduling.dto.PlacedItemDto placed : preview.getPlacedItems()) {
            AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
            edit.setProposalItemId(placed.getProposalItemId());
            edit.setPlacementType(placed.getPlacementType());
            edit.setScheduledDate(placed.getScheduledDate());
            edit.setScheduledStartAt(placed.getScheduledStartAt());
            edit.setScheduledEndAt(placed.getScheduledEndAt());
            edits.add(edit);
        }
        return edits;
    }

    private static AiProposalApplyRequest.EditedProposalItem editedTitle(Long proposalItemId, String title) {
        AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(proposalItemId);
        edit.setTitle(title);
        return edit;
    }

    private long countExecutionItems() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM execution_items WHERE user_id = ? AND is_deleted = 0")) {
            ps.setLong(1, TEST_USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("조회 실패", e);
        }
    }
}
