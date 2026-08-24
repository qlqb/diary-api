package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanPlacementResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정과 롤링 배치를 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * Mockito로는 증명할 수 없는 것들이 대상이다 — 스냅샷 항목 수와 실제 생성 항목 수와
 * plan_version_id 기록 행 수가 **셋 다** 일치하는지, 같은 제안을 두 번 확정할 때 UNIQUE와
 * 상태 검사가 실제로 막는지, 롤링 배치가 기존 TIME_FIXED를 실제로 피하는지.
 *
 * 날짜는 실행 시점 기준으로 계산한다 — 고정 날짜를 쓰면 "과거에는 배치하지 않는다" 규칙에
 * 걸려 시간이 지나면 테스트가 깨진다.
 *
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanConfirmAndPlacementIntegrationTest {

    private static final String TITLE_PREFIX = "PCI-";

    @Autowired
    private AiProposalService aiProposalService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private PlanPlacementService planPlacementService;
    @Autowired
    private PlanVersionMapper planVersionMapper;
    @Autowired
    private PlanSnapshotCodec snapshotCodec;
    @Autowired
    private ExecutionItemMapper executionItemMapper;
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

    // ===== 확정 =====

    @Test
    void confirm_itemsAndSnapshotAndPlanVersionIdAllMatch() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(13);
        Long proposalId = givenPlanProposal(start, end, 5);

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "확정 계획").build());

        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(plan.getItemsSnapshot());
        List<Long> snapshotIds = snapshot.stream().map(PlanSnapshotItem::executionItemId).toList();

        assertThat(snapshot).as("스냅샷 항목 수").hasSize(5);
        assertThat(snapshotIds).as("스냅샷의 모든 항목이 executionItemId를 갖는다").doesNotContainNull();

        List<ExecutionItem> created = executionItemMapper.findByIdsForReview(userId(), snapshotIds);
        assertThat(created).as("실제 생성된 실행 조각 수").hasSize(5);
        assertThat(created).as("전부 이 계획을 출처로 갖는다")
                .allSatisfy(item -> assertThat(item.getPlanVersionId()).isEqualTo(plan.getPlanVersionId()));

        assertThat(countRows("execution_items", "plan_version_id = " + plan.getPlanVersionId()))
                .as("plan_version_id 기록 행 수").isEqualTo(5);
    }

    @Test
    void confirm_copiesIntensityAndTargetMinutesFromTheProposal() {
        LocalDate start = today().plusDays(7);
        Long proposalId = givenPlanProposal(start, start.plusDays(6), 2);

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "강도 복사").build());

        // 확정 요청은 강도도 목표도 받지 않는다 — 제안에서 읽는다.
        assertThat(plan.getIntensity()).isEqualTo(PlanIntensity.FOCUSED);
        assertThat(plan.getTargetMinutes()).isEqualTo(1080);
        assertThat(plan.getVersion()).isEqualTo(1);
        assertThat(plan.getPlanKey()).isNotBlank();
    }

    @Test
    void confirm_unscheduledItemsGetThePlanPeriodAsPlanningRange() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(13);
        Long proposalId = givenPlanProposal(start, end, 3);

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "기간 채움").build());

        List<Long> ids = snapshotCodec.fromJson(plan.getItemsSnapshot()).stream()
                .map(PlanSnapshotItem::executionItemId).toList();
        assertThat(executionItemMapper.findByIdsForReview(userId(), ids))
                .allSatisfy(item -> {
                    assertThat(item.getPlacementType()).isEqualTo(PlacementType.UNSCHEDULED);
                    assertThat(item.getPlanningStartDate()).isEqualTo(start);
                    assertThat(item.getPlanningEndDate()).isEqualTo(end);
                    // 확정 시점에는 솔버를 돌리지 않으므로 시각이 없다.
                    assertThat(item.getScheduledStartAt()).isNull();
                });
    }

    @Test
    void confirmingTheSameProposalTwice_isRejected() {
        LocalDate start = today().plusDays(7);
        Long proposalId = givenPlanProposal(start, start.plusDays(6), 2);
        planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "첫 확정").build());

        // 제안 상태 검사가 먼저 막는다. UNIQUE(source_proposal_id)는 그 뒤의 방어선이다.
        assertThatThrownBy(() -> planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "두 번째 확정").build()))
                .isInstanceOf(ConflictException.class);

        assertThat(countRows("plan_versions", "source_proposal_id = " + proposalId)).isEqualTo(1);
    }

    // ===== 롤링 배치 =====

    @Test
    void place_assignsTimesWithinTheWindowAndClearsPlanningRange() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(20);
        Long proposalId = givenPlanProposal(start, end, 3);
        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "롤링").build());

        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        assertThat(result.getWindowStart()).isEqualTo(start);
        assertThat(result.getWindowEnd()).as("창은 7일로 끊긴다").isEqualTo(start.plusDays(6));
        assertThat(result.getPlaced()).as("기본 가용시간이 있으므로 최소 하나는 들어간다").isNotEmpty();

        for (PlanPlacementResponse.PlacedItem placed : result.getPlaced()) {
            assertThat(placed.getScheduledDate()).isBetween(result.getWindowStart(), result.getWindowEnd());
            ExecutionItem item = executionItemMapper.findByIdsForReview(
                    userId(), List.of(placed.getExecutionItemId())).get(0);
            assertThat(item.getPlacementType()).isEqualTo(PlacementType.TIME_FIXED);
            // 전이 규칙: 날짜를 정하면 planning_* 를 비운다. 안 비우면 CHECK가 UPDATE를 막는다.
            assertThat(item.getPlanningStartDate()).isNull();
            assertThat(item.getPlanningEndDate()).isNull();
        }
    }

    @Test
    void place_doesNotOverlapExistingTimeFixedItems() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(20);
        Long proposalId = givenPlanProposal(start, end, 4);
        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "겹침 없음").build());

        // 창 안의 하루를 통째로 막는다(00:00~23:59). 기본 가용시간대 전체를 덮는다.
        LocalDate blockedDay = start.plusDays(2);
        LocalDateTime busyFrom = blockedDay.atTime(0, 0);
        LocalDateTime busyTo = blockedDay.atTime(23, 59);
        insertTimeFixed(TITLE_PREFIX + "고정 일정", blockedDay, busyFrom, busyTo);

        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        assertThat(result.getPlaced())
                .as("막아둔 하루에는 아무것도 배치되지 않아야 한다")
                .noneMatch(p -> p.getScheduledDate().equals(blockedDay));
        for (PlanPlacementResponse.PlacedItem placed : result.getPlaced()) {
            assertThat(placed.getScheduledStartAt().isBefore(busyTo)
                    && busyFrom.isBefore(placed.getScheduledEndAt()))
                    .as("배치 결과가 기존 고정 일정과 겹치면 안 된다: %s", placed.getTitle())
                    .isFalse();
        }
    }

    @Test
    void place_itemsThatDoNotFit_stayUnscheduledAndAreReturned() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(20);
        // 120분 항목을 여럿 넣고 창 전체를 거의 막아 자리를 없앤다.
        Long proposalId = givenPlanProposal(start, end, 6, 120);
        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "자리 부족").build());

        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            insertTimeFixed(TITLE_PREFIX + "막기" + i, day, day.atTime(0, 0), day.atTime(23, 59));
        }

        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        assertThat(result.getPlaced()).isEmpty();
        assertThat(result.getUnplaced()).as("못 들어간 항목은 응답으로 돌려준다").hasSize(6);

        // 그리고 UNSCHEDULED로 남아 있어야 한다 — 다음 창에서 다시 시도할 수 있어야 한다.
        List<Long> unplacedIds = result.getUnplaced().stream()
                .map(PlanPlacementResponse.UnplacedItem::getExecutionItemId).toList();
        assertThat(executionItemMapper.findByIdsForReview(userId(), unplacedIds))
                .allSatisfy(item -> {
                    assertThat(item.getPlacementType()).isEqualTo(PlacementType.UNSCHEDULED);
                    assertThat(item.getPlanningStartDate()).isEqualTo(start);
                });
    }

    @Test
    void place_windowIsClampedToThePlanEnd() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(2);
        Long proposalId = givenPlanProposal(start, end, 2);
        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "짧은 계획").build());

        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        assertThat(result.getWindowEnd()).isEqualTo(end);
    }

    // ===== fixture =====

    private Long givenPlanProposal(LocalDate start, LocalDate end, int itemCount) {
        return givenPlanProposal(start, end, itemCount, 40);
    }

    private Long givenPlanProposal(LocalDate start, LocalDate end, int itemCount, int minutes) {
        List<ProposalItem> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new ProposalItem(
                    TITLE_PREFIX + "항목" + i, "이유 " + i, minutes, "SHOULD",
                    PlacementType.UNSCHEDULED, null, null, start, end, null, null, null));
        }
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId(), null, null, items, List.of(), start, List.of(), 30);
        createdProposalIds.add(proposal.getProposalId());
        aiProposalMapper.updatePlanMetadata(
                proposal.getProposalId(), userId(), start, end, PlanIntensity.FOCUSED, 1080);
        return proposal.getProposalId();
    }

    private void insertTimeFixed(String title, LocalDate date, LocalDateTime from, LocalDateTime to) {
        ExecutionItem item = ExecutionItem.builder()
                .userId(userId()).title(title)
                .placementType(PlacementType.TIME_FIXED)
                .scheduledDate(date).scheduledStartAt(from).scheduledEndAt(to)
                .expectedMinutes(60)
                .status(ExecutionStatus.PLANNED).priority(ExecutionPriority.MUST)
                .orderIndex(0).originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false).version(0L).isDeleted(false)
                .build();
        executionItemMapper.insert(item);
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
