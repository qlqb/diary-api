package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanReviewResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기존 계획 항목을 조정(REDUCE/MOVE/DROP)·유지(KEEP)하고 새 항목(CREATE)을 더한 재계획 초안을 실제 로컬 memo DB에서 확정한다.
 *
 * <p>수정 전 코드에서는 첫 시나리오가 {@code IllegalStateException}(plan_version_id 기록 행 수 불일치)으로 롤백된다 — 조정
 * 대상 항목의 id를 신규 생성 id와 함께 모아 새 plan_version_id를 붙이려 했고, {@code plan_version_id IS NULL} 조건이 그것을
 * 막았다. 수정 뒤에는 같은 plan_key의 다음 판이 생기고, 기존 항목의 출처(plan_version_id)는 그대로, 새 항목만 새 판을 출처로
 * 갖는다. 스냅샷은 "계속할 범위"(유지·조정된 기존 항목 + 새 항목)다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanReplanConfirmIntegrationTest {

    private static final String TITLE_PREFIX = "PRC-";

    @Autowired
    private AiProposalService aiProposalService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private PlanVersionService planVersionService;
    @Autowired
    private PlanReviewService planReviewService;
    @Autowired
    private ExecutionItemService executionItemService;
    @Autowired
    private ExecutionItemMapper executionItemMapper;
    @Autowired
    private PlanSnapshotCodec snapshotCodec;
    @Autowired
    private PlanStrategyCodec strategyCodec;
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
                exec(conn, "DELETE FROM ai_proposal_items WHERE proposal_id = " + proposalId);
                exec(conn, "DELETE FROM ai_proposals WHERE proposal_id = " + proposalId);
            }
        }
        createdProposalIds.clear();
    }

    @Test
    void replan_withReduceMoveDropKeepAndCreate_confirmsAsNextVersionOfTheSamePlan() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        PlanVersion v1 = confirmFirstPlan(start, end);
        Map<String, ExecutionItem> byTitle = itemsByTitle(v1);
        ExecutionItem reduceTarget = byTitle.get(TITLE_PREFIX + "줄일 것");
        ExecutionItem dropTarget = byTitle.get(TITLE_PREFIX + "뺄 것");
        ExecutionItem moveTarget = byTitle.get(TITLE_PREFIX + "옮길 것");
        ExecutionItem keepTarget = byTitle.get(TITLE_PREFIX + "그대로 둘 것");
        long v1Version = reduceTarget.getVersion();

        Long replanId = givenReplanProposal(start.plusDays(1), end, v1, reduceTarget, dropTarget, moveTarget, keepTarget,
                List.of(newItem(TITLE_PREFIX + "새 항목")));

        PlanVersion v2 = planConfirmService.confirm(userId(), replanId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "재계획").build());

        // 같은 계획의 다음 판이다 — 새 UUID가 아니다.
        assertThat(v2.getPlanKey()).isEqualTo(v1.getPlanKey());
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getSourceProposalId()).isEqualTo(replanId);

        // 기존 항목의 생성 출처는 v1 그대로, 새 항목만 v2를 출처로 갖는다.
        Map<String, ExecutionItem> after = itemsByTitle(v1);
        assertThat(after.get(TITLE_PREFIX + "줄일 것").getPlanVersionId()).isEqualTo(v1.getPlanVersionId());
        assertThat(after.get(TITLE_PREFIX + "그대로 둘 것").getPlanVersionId()).isEqualTo(v1.getPlanVersionId());
        assertThat(after.get(TITLE_PREFIX + "옮길 것").getPlanVersionId()).isEqualTo(v1.getPlanVersionId());
        ExecutionItem created = after.get(TITLE_PREFIX + "새 항목");
        assertThat(created).isNotNull();
        assertThat(created.getPlanVersionId()).isEqualTo(v2.getPlanVersionId());
        assertThat(countRows("execution_items", "plan_version_id = " + v2.getPlanVersionId())).isEqualTo(1);

        // 조정이 실제로 적용됐고 낙관적 락 카운터가 올랐다.
        assertThat(after.get(TITLE_PREFIX + "줄일 것").getExpectedMinutes()).isEqualTo(20);
        assertThat(after.get(TITLE_PREFIX + "줄일 것").getVersion()).isEqualTo(v1Version + 1);
        assertThat(after.get(TITLE_PREFIX + "옮길 것").getScheduledDate()).isEqualTo(start.plusDays(2));
        ExecutionItem held = executionItemMapper.findByIdAndUserId(dropTarget.getExecutionItemId(), userId());
        assertThat(held.getStatus()).isEqualTo(ExecutionStatus.HOLD);
        assertThat(held.getIsDeleted()).isFalse();
        assertThat(held.getPlanVersionId()).isEqualTo(v1.getPlanVersionId());

        // 스냅샷 = 계속할 범위: 새 항목 + 줄인 것 + 옮긴 것 + 그대로 둔 것. 보류한 것은 빠진다.
        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(v2.getItemsSnapshot());
        assertThat(snapshot).extracting(PlanSnapshotItem::title).containsExactlyInAnyOrder(
                TITLE_PREFIX + "새 항목", TITLE_PREFIX + "줄일 것", TITLE_PREFIX + "옮길 것", TITLE_PREFIX + "그대로 둘 것");
        assertThat(snapshot).filteredOn(s -> s.title().equals(TITLE_PREFIX + "줄일 것"))
                .allSatisfy(s -> assertThat(s.expectedMinutes()).isEqualTo(20));

        // 계획 화면(planKey 기준)·회고(v2 스냅샷)·오늘을 덮는 계획 목록이 같은 남은 범위를 본다.
        assertThat(planVersionService.findItems(userId(), v2.getPlanVersionId()))
                .extracting(ExecutionItem::getTitle)
                .contains(TITLE_PREFIX + "새 항목", TITLE_PREFIX + "줄일 것", TITLE_PREFIX + "그대로 둘 것");
        PlanReviewResponse review = planReviewService.review(userId(), v2.getPlanVersionId());
        assertThat(review.getItems()).extracting(PlanReviewResponse.PlanReviewItem::getTitle)
                .contains(TITLE_PREFIX + "새 항목", TITLE_PREFIX + "그대로 둘 것");
        // 보류한 항목은 v2의 계획 범위가 아니다 — 회고가 기간 안 항목으로 보여 주더라도 "계획 밖"이다.
        assertThat(review.getItems()).filteredOn(i -> i.getTitle().equals(TITLE_PREFIX + "뺄 것"))
                .allSatisfy(i -> assertThat(i.getCategory()).isEqualTo(
                        com.jungwoo.project.memo.plan.dto.PlanReviewCategory.OUTSIDE_PLAN));
        List<PlanVersion> covering = planVersionService.findCoveringDate(userId(), start.plusDays(3), null).stream()
                .filter(p -> p.getPlanKey().equals(v1.getPlanKey())).toList();
        assertThat(covering).as("같은 계획은 최신 판 하나로만 보인다").hasSize(1);
        assertThat(covering.get(0).getPlanVersionId()).isEqualTo(v2.getPlanVersionId());
        assertThat(planVersionService.findByPlanKey(userId(), v1.getPlanKey())).hasSize(2);

        // 같은 초안을 다시 확정하면 막히고, 변경·이벤트가 중복되지 않는다.
        int holdEvents = countRows("execution_item_events",
                "execution_item_id = " + dropTarget.getExecutionItemId() + " AND event_type = 'HOLD'");
        assertThatThrownBy(() -> planConfirmService.confirm(userId(), replanId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "재적용").build()))
                .isInstanceOf(ConflictException.class);
        assertThat(countRows("execution_item_events",
                "execution_item_id = " + dropTarget.getExecutionItemId() + " AND event_type = 'HOLD'")).isEqualTo(holdEvents);
        assertThat(countRows("plan_versions", "plan_key = '" + v1.getPlanKey() + "'")).isEqualTo(2);
    }

    @Test
    void replan_withAdjustmentsOnly_confirmsWithoutCreatingItems() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        PlanVersion v1 = confirmFirstPlan(start, end);
        Map<String, ExecutionItem> byTitle = itemsByTitle(v1);

        Long replanId = givenReplanProposal(start.plusDays(1), end, v1, byTitle.get(TITLE_PREFIX + "줄일 것"),
                byTitle.get(TITLE_PREFIX + "뺄 것"), byTitle.get(TITLE_PREFIX + "옮길 것"),
                byTitle.get(TITLE_PREFIX + "그대로 둘 것"), List.of());

        PlanVersion v2 = planConfirmService.confirm(userId(), replanId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "조정만").build());

        assertThat(v2.getPlanKey()).isEqualTo(v1.getPlanKey());
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(countRows("execution_items", "plan_version_id = " + v2.getPlanVersionId())).isZero();
        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(v2.getItemsSnapshot());
        assertThat(snapshot).extracting(PlanSnapshotItem::title).containsExactlyInAnyOrder(
                TITLE_PREFIX + "줄일 것", TITLE_PREFIX + "옮길 것", TITLE_PREFIX + "그대로 둘 것");
        assertThat(executionItemMapper.findByIdAndUserId(byTitle.get(TITLE_PREFIX + "뺄 것").getExecutionItemId(), userId())
                .getStatus()).isEqualTo(ExecutionStatus.HOLD);
    }

    @Test
    void replan_excludingEveryChange_isRejected() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        PlanVersion v1 = confirmFirstPlan(start, end);
        Map<String, ExecutionItem> byTitle = itemsByTitle(v1);
        Long replanId = givenReplanProposal(start.plusDays(1), end, v1, byTitle.get(TITLE_PREFIX + "줄일 것"),
                byTitle.get(TITLE_PREFIX + "뺄 것"), byTitle.get(TITLE_PREFIX + "옮길 것"),
                byTitle.get(TITLE_PREFIX + "그대로 둘 것"), List.of());
        List<Long> all = aiProposalService.get(replanId, userId()).getItems().stream()
                .map(i -> i.getProposalItemId()).toList();

        assertThatThrownBy(() -> planConfirmService.confirm(userId(), replanId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "전부 제외").excludedItemIds(all).build()))
                .isInstanceOf(BadRequestException.class);
        assertThat(countRows("plan_versions", "plan_key = '" + v1.getPlanKey() + "'")).isEqualTo(1);
    }

    @Test
    void replan_whenATargetChangedMeanwhile_rollsBackEverything() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        PlanVersion v1 = confirmFirstPlan(start, end);
        Map<String, ExecutionItem> byTitle = itemsByTitle(v1);
        ExecutionItem reduceTarget = byTitle.get(TITLE_PREFIX + "줄일 것");
        ExecutionItem dropTarget = byTitle.get(TITLE_PREFIX + "뺄 것");
        Long replanId = givenReplanProposal(start.plusDays(1), end, v1, reduceTarget, dropTarget,
                byTitle.get(TITLE_PREFIX + "옮길 것"), byTitle.get(TITLE_PREFIX + "그대로 둘 것"),
                List.of(newItem(TITLE_PREFIX + "새 항목")));

        // 초안을 만든 뒤 사용자가 그 항목을 직접 고쳤다 — 제안의 base_version이 낡았다.
        executionItemService.reduce(reduceTarget.getExecutionItemId(), userId(), ExecutionItemReduceRequest.builder()
                .expectedMinutes(30).version(reduceTarget.getVersion()).build());
        int holdEventsBefore = countRows("execution_item_events",
                "execution_item_id = " + dropTarget.getExecutionItemId() + " AND event_type = 'HOLD'");

        assertThatThrownBy(() -> planConfirmService.confirm(userId(), replanId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "경합").build()))
                .isInstanceOf(ConflictException.class);

        // 전부 되돌아간다: 새 항목 없음, 보류 없음, 제안은 PROPOSED, 계획 판은 그대로 1개.
        assertThat(countRows("execution_items", "title = '" + TITLE_PREFIX + "새 항목'")).isZero();
        assertThat(executionItemMapper.findByIdAndUserId(dropTarget.getExecutionItemId(), userId()).getStatus())
                .isEqualTo(ExecutionStatus.PLANNED);
        assertThat(countRows("execution_item_events",
                "execution_item_id = " + dropTarget.getExecutionItemId() + " AND event_type = 'HOLD'")).isEqualTo(holdEventsBefore);
        assertThat(aiProposalMapper.findByIdAndUserId(replanId, userId()).getStatus()).isEqualTo(AiProposalStatus.PROPOSED);
        assertThat(countRows("ai_proposal_items", "proposal_id = " + replanId + " AND status <> 'PROPOSED'")).isZero();
        assertThat(countRows("plan_versions", "plan_key = '" + v1.getPlanKey() + "'")).isEqualTo(1);
    }

    // ===== fixture =====

    /** 첫 계획: 날짜 미정 3개 + 날짜 있는 1개("옮길 것"). */
    private PlanVersion confirmFirstPlan(LocalDate start, LocalDate end) {
        List<ProposalItem> items = List.of(
                newItem(TITLE_PREFIX + "줄일 것"),
                newItem(TITLE_PREFIX + "뺄 것"),
                new ProposalItem(TITLE_PREFIX + "옮길 것", "이유", 40, "SHOULD", PlacementType.DATE_ONLY, null, null,
                        null, null, null, null, null),
                newItem(TITLE_PREFIX + "그대로 둘 것"));
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId(), null, null, items, List.of(), start, List.of(), 30);
        createdProposalIds.add(proposal.getProposalId());
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId(), start, end, PlanIntensity.NORMAL, 600,
                null, null);
        return planConfirmService.confirm(userId(), proposal.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "첫 계획").build());
    }

    private static ProposalItem newItem(String title) {
        return new ProposalItem(title, "이유", 40, "SHOULD", PlacementType.UNSCHEDULED, null, null,
                null, null, null, null, null);
    }

    /** 재계획 초안: 조정(REDUCE·DROP·MOVE) + 유지(KEEP, 전략에만) + 새 항목. 생성기가 만드는 것과 같은 모양이다. */
    private Long givenReplanProposal(LocalDate start, LocalDate end, PlanVersion base, ExecutionItem reduce,
                                     ExecutionItem drop, ExecutionItem move, ExecutionItem keep, List<ProposalItem> items) {
        List<ProposalAdjustment> adjustments = List.of(
                new ProposalAdjustment(reduce.getExecutionItemId(), ProposalOperation.REDUCE, 20, null, null, null, null, "겹침"),
                new ProposalAdjustment(drop.getExecutionItemId(), ProposalOperation.DROP, null, null, null, null, null, "끝남"),
                new ProposalAdjustment(move.getExecutionItemId(), ProposalOperation.MOVE, null, null, start.plusDays(1), null, null,
                        "수업 뒤로"));
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId(), null, null, items, adjustments, start, List.of(), 30, List.of());
        createdProposalIds.add(proposal.getProposalId());
        PlanStrategy strategy = new PlanStrategy("남은 기간", "요약", null, null, List.of(), List.of(), List.of(), List.of(),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(
                new PlanStrategy.ExistingDecision(keep.getExecutionItemId(), keep.getTitle(), "KEEP", "그대로", null, null),
                new PlanStrategy.ExistingDecision(reduce.getExecutionItemId(), reduce.getTitle(), "REDUCE", "겹침", 20, null),
                new PlanStrategy.ExistingDecision(drop.getExecutionItemId(), drop.getTitle(), "DROP", "끝남", null, null),
                new PlanStrategy.ExistingDecision(move.getExecutionItemId(), move.getTitle(), "MOVE", "수업 뒤로", null,
                        start.plusDays(1))));
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId(), start, end, PlanIntensity.NORMAL, 500,
                strategyCodec.toJson(strategy), null);
        return proposal.getProposalId();
    }

    private Map<String, ExecutionItem> itemsByTitle(PlanVersion plan) {
        List<ExecutionItem> items = executionItemMapper.findInPeriodForReview(userId(), plan.getStartDate().minusDays(1),
                plan.getEndDate().plusDays(1));
        return items.stream().filter(i -> i.getTitle().startsWith(TITLE_PREFIX) && !Boolean.TRUE.equals(i.getIsDeleted()))
                .collect(Collectors.toMap(ExecutionItem::getTitle, Function.identity(), (a, b) -> b));
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
