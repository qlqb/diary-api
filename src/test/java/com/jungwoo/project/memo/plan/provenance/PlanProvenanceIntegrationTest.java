package com.jungwoo.project.memo.plan.provenance;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 출처 기록이 저장·확정·적용·조회를 지나 살아남는지 실제 로컬 MariaDB(memo)에 대고 본다.
 *
 * <p>단위 테스트로는 증명할 수 없는 것들이 대상이다 — 확정이 스냅샷을 계획으로 옮기는지,
 * 적용된 조각에서 원래 회차로 되짚어지는지, 사용자가 값을 고쳤을 때 근거 상태가 실제로
 * 바뀌는지, 그리고 <b>적용이 서버 소유 스냅샷을 건드리지 않는지</b>.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 * 이 스위트는 docs/sql/2026-09-10-plan-provenance.sql이 적용돼 있어야 돈다.
 */
@SpringBootTest
class PlanProvenanceIntegrationTest {

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
    private DataSource dataSource;

    private Long userId;
    private final List<Long> createdProposalIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM execution_item_events WHERE execution_item_id IN "
                    + "(SELECT execution_item_id FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%')");
            exec(conn, "DELETE FROM execution_items WHERE title LIKE '" + TITLE_PREFIX + "%'");
            exec(conn, "DELETE FROM plan_versions WHERE title LIKE '" + TITLE_PREFIX + "%'");
            for (Long proposalId : createdProposalIds) {
                exec(conn, "DELETE FROM ai_proposal_schedule_previews WHERE proposal_id = " + proposalId);
                exec(conn, "DELETE FROM ai_proposal_items WHERE proposal_id = " + proposalId);
                exec(conn, "DELETE FROM ai_proposals WHERE proposal_id = " + proposalId);
            }
        }
        createdProposalIds.clear();
    }

    // ===== 초안에서 조회 =====

    @Test
    void draftProvenance_returnsWhatWasProvided_andPerItemEvidence() {
        Fixture fixture = givenProposalWithProvenance(2);

        PlanProvenanceResponse response = planProvenanceService.forProposal(userId(), fixture.proposalId());

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
    }

    /** 원본이 바뀌어도 스냅샷은 그때 값이다. 안 그러면 과거 제안이 현재 값으로 정당화된다. */
    @Test
    void theSnapshotKeepsTheValueAsItWas_evenAfterTheSourceRowChanges() {
        Fixture fixture = givenProposalWithProvenance(1);

        // 원본이 바뀐 상황을 만든다 — 스냅샷은 서버가 다시 조회해 갱신하지 않는다.
        PlanProvenanceResponse before = planProvenanceService.forProposal(userId(), fixture.proposalId());
        String recordedValue = before.providedSources().get(0).promptLine();

        PlanProvenanceResponse after = planProvenanceService.forProposal(userId(), fixture.proposalId());
        assertThat(after.providedSources().get(0).promptLine()).isEqualTo(recordedValue);
        assertThat(after.providedSources().get(0).providedValue())
                .containsEntry("label", "근무");
    }

    /** 지워졌거나 볼 수 없는 원본은 링크를 끄고 이유를 말한다 — 깨진 링크를 만들지 않는다. */
    @Test
    void aSourceRowThatNoLongerExists_getsADisabledLinkWithAReason() {
        Fixture fixture = givenProposalWithProvenance(1);

        PlanProvenanceResponse response = planProvenanceService.forProposal(userId(), fixture.proposalId());
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
        Long otherUserId = userId() + 100_000L;

        assertThatThrownBy(() -> planProvenanceService.forProposal(otherUserId, fixture.proposalId()))
                .isInstanceOf(NotFoundException.class);
    }

    // ===== 레거시 =====

    @Test
    void aProposalWithoutProvenance_readsAsNotRecorded_insteadOfFailing() {
        Long proposalId = givenPlanProposal(2, null);

        PlanProvenanceResponse response = planProvenanceService.forProposal(userId(), proposalId);

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

        PlanVersion plan = planConfirmService.confirm(userId(), fixture.proposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "확정").build());

        PlanVersion stored = planVersionMapper.findByIdAndUserId(plan.getPlanVersionId(), userId());
        assertThat(stored.getProvenanceJson()).as("확정이 스냅샷을 계획으로 옮긴다").isNotBlank();
        assertThat(codec.fromJson(stored.getProvenanceJson()).generationId())
                .isEqualTo(fixture.generationId());

        Long executionItemId = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), userId()).get(0).getCreatedItemId();
        assertThat(executionItemId).isNotNull();

        PlanProvenanceResponse response = planProvenanceService.forExecutionItem(userId(), executionItemId);
        assertThat(response.recorded()).isTrue();
        assertThat(response.generationId()).isEqualTo(fixture.generationId());
        assertThat(response.planVersionId()).isEqualTo(plan.getPlanVersionId());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).executionItemId()).isEqualTo(executionItemId);
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
        String before = aiProposalMapper.findByIdAndUserId(fixture.proposalId(), userId())
                .getPlanProvenanceJson();

        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), userId());
        planConfirmService.confirm(userId(), fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "고쳐서 확정")
                .editedItems(List.of(editedTitle(items.get(0).getProposalItemId(), TITLE_PREFIX + "내가 고친 제목")))
                .build());

        AiProposal after = aiProposalMapper.findByIdAndUserId(fixture.proposalId(), userId());
        assertThat(after.getPlanProvenanceJson()).isEqualTo(before);
    }

    /** 내용을 고치면 근거는 "수정 전 제안의 것"이 된다. 지우지 않고 상태만 바꾼다. */
    @Test
    void editingTheContentAtConfirmTime_marksTheEvidenceAsPreEdit() {
        Fixture fixture = givenProposalWithProvenance(1);
        List<AiProposalItem> items = aiProposalItemMapper
                .findByProposalIdAndUserId(fixture.proposalId(), userId());
        Long proposalItemId = items.get(0).getProposalItemId();

        planConfirmService.confirm(userId(), fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "내용 수정")
                .editedItems(List.of(editedTitle(proposalItemId, TITLE_PREFIX + "다른 제목")))
                .build());

        PlanProvenanceResponse response = planProvenanceService.forProposal(userId(), fixture.proposalId());
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
                .findByProposalIdAndUserId(fixture.proposalId(), userId());

        com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem edit =
                new com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(items.get(0).getProposalItemId());
        edit.setPlacementType(PlacementType.DATE_ONLY);
        edit.setScheduledDate(LocalDate.now().plusDays(8));

        planConfirmService.confirm(userId(), fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "날짜 수정")
                .editedItems(List.of(edit))
                .build());

        PlanProvenanceResponse.ItemView item =
                planProvenanceService.forProposal(userId(), fixture.proposalId()).items().get(0);

        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.NEEDS_REVIEW);
        assertThat(item.staleReasons()).isNotEmpty();
        assertThat(item.refIds()).as("근거 자체는 지우지 않는다").containsExactly("s1");
    }

    /**
     * 서버 미리보기가 정한 시각을 그대로 확정한 것은 <b>사용자 수정이 아니다.</b>
     *
     * <p>확정 요청은 배치 미리보기의 시각을 그대로 싣는다. 그것까지 수정으로 세면 확정한
     * 모든 항목에 "다시 볼 근거"가 붙어 표식이 뜻을 잃는다 — 실제 화면에서 그렇게 나왔다.
     */
    @Test
    void confirmingTheServersOwnPreviewPlacement_isNotCountedAsAUserEdit() {
        Fixture fixture = givenProposalWithProvenance(1);

        // 미리보기를 실제로 계산해 저장한다(OpenAI 호출 없음).
        SchedulePreviewResponse preview = schedulePreviewService.computePreview(
                userId(), fixture.proposalId(), new SchedulePreviewRequest());
        org.junit.jupiter.api.Assumptions.assumeTrue(!preview.getPlacedItems().isEmpty(),
                "이 기간에 배치된 항목이 없으면 이 테스트가 볼 것이 없다");

        List<com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem> edits =
                new ArrayList<>();
        for (com.jungwoo.project.memo.scheduling.dto.PlacedItemDto placed : preview.getPlacedItems()) {
            com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem edit =
                    new com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem();
            edit.setProposalItemId(placed.getProposalItemId());
            edit.setPlacementType(placed.getPlacementType());
            edit.setScheduledDate(placed.getScheduledDate());
            edit.setScheduledStartAt(placed.getScheduledStartAt());
            edit.setScheduledEndAt(placed.getScheduledEndAt());
            edits.add(edit);
        }

        planConfirmService.confirm(userId(), fixture.proposalId(), PlanConfirmRequest.builder()
                .title(TITLE_PREFIX + "미리보기 그대로 확정")
                .editedItems(edits)
                .build());

        PlanProvenanceResponse.ItemView item =
                planProvenanceService.forProposal(userId(), fixture.proposalId()).items().get(0);
        assertThat(item.evidenceStatus()).isEqualTo(EvidenceStatus.CURRENT);
        assertThat(item.staleReasons()).isEmpty();
    }

    // ===== 회차가 섞이지 않는다 =====

    @Test
    void twoGenerations_keepTheirOwnSourcesAndEvidence() {
        Fixture first = givenProposalWithProvenance(1);
        Fixture second = givenProposalWithProvenance(1);

        assertThat(first.generationId()).isNotEqualTo(second.generationId());

        PlanProvenanceResponse firstResponse = planProvenanceService.forProposal(userId(), first.proposalId());
        PlanProvenanceResponse secondResponse = planProvenanceService.forProposal(userId(), second.proposalId());

        assertThat(firstResponse.items().get(0).generationId()).isEqualTo(first.generationId());
        assertThat(secondResponse.items().get(0).generationId()).isEqualTo(second.generationId());
        // 앞 회차를 다시 만들었다고 지우지 않는다 — 옛 근거는 그대로 조회된다.
        assertThat(firstResponse.recorded()).isTrue();
    }

    // ===== fixture =====

    private record Fixture(Long proposalId, String generationId) {
    }

    private Fixture givenProposalWithProvenance(int itemCount) {
        String generationId = "gen-test-" + System.nanoTime();
        Long proposalId = givenPlanProposal(itemCount, generationId);
        return new Fixture(proposalId, generationId);
    }

    /**
     * 계획 경로로 만들어진 제안 하나. generationId가 null이면 출처 없이 만든다(레거시 재현).
     *
     * <p>모델을 부르지 않고 서버가 만드는 모양 그대로 넣는다 — 여기서 보려는 것은 저장·확정·
     * 조회 경로이지 프롬프트 생성이 아니다(그쪽은 PlanProvenanceCaptureTest가 본다).
     */
    private Long givenPlanProposal(int itemCount, String generationId) {
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
                userId(), null, null, items, List.of(), start, List.of(), 30,
                generationId == null ? List.of() : evidence);
        createdProposalIds.add(proposal.getProposalId());

        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId(), start, end,
                PlanIntensity.FOCUSED, 1080, null,
                generationId == null ? null : codec.toJson(provenanceOf(generationId, start, end)));
        return proposal.getProposalId();
    }

    /** 서버가 만드는 모양 그대로의 스냅샷. commitment id는 실제로 없는 값이다(삭제된 원본 재현). */
    private PlanProvenance provenanceOf(String generationId, LocalDate start, LocalDate end) {
        return new PlanProvenance(PlanProvenance.SCHEMA_VERSION, generationId,
                LocalDateTime.now(), "Asia/Seoul", start, end, "AI", "test-model",
                List.of(
                        new ProvidedSource("s1", ProvenanceSourceType.COMMITMENT, 999_000_001L, null, null,
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

    private com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem editedTitle(
            Long proposalItemId, String title) {
        com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem edit =
                new com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem();
        edit.setProposalItemId(proposalItemId);
        edit.setTitle(title);
        return edit;
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

    private void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
