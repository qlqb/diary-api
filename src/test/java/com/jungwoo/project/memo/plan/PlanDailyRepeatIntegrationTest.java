package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "7일 동안 매일 영어 15분"이라는 합의가 초안 → 확정 → 배치까지 날짜별 실행으로 보존되는지 실제 로컬 memo DB에서 본다
 * (2026-09-17 후속 §8).
 *
 * <p>수정 전에는 기간 계획의 DATE_ONLY 항목이 모두 계획 시작일로 저장돼(validateAndNormalize가 항목의 날짜를 버렸다) 7개가
 * 첫날 하루에 몰렸고, 재계획이 그것을 같은 날 중복으로 보고 6개를 보류했다. 지금은 각 항목이 제 날짜를 갖고, 배치는 그 날짜 안에서만
 * 시각을 고르며, 그 날에 시간이 없으면 다른 날로 옮기거나 횟수를 줄이지 않고 이유를 붙여 미배치로 남긴다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanDailyRepeatIntegrationTest {

    private static final String TITLE_PREFIX = "PDR-";

    @Autowired
    private AiProposalService aiProposalService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private PlanConfirmService planConfirmService;
    @Autowired
    private PlanPlacementService planPlacementService;
    @Autowired
    private PlanVersionService planVersionService;
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

    @Test
    void dailyFifteenMinutes_staysOneItemPerDay_throughConfirmAndPlacement_andABlockedDayIsReportedNotMerged() {
        LocalDate start = LocalDate.now().plusDays(7);
        LocalDate end = start.plusDays(6);
        List<ProposalItem> items = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            LocalDate day = start.plusDays(d);
            items.add(new ProposalItem(TITLE_PREFIX + "영어 15분 말하기", "매일 15분 합의", 15, "SHOULD",
                    PlacementType.DATE_ONLY, null, null, day, day, null, null, null));
        }
        AiProposalResponse proposal = aiProposalService.createFromItems(userId(), null, null, items, List.of(), start,
                List.of(), 30);
        createdProposalIds.add(proposal.getProposalId());
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId(), start, end, PlanIntensity.NORMAL, 600, null,
                null);

        // 초안: 항목마다 제 날짜를 갖는다(시작일로 몰리지 않는다).
        assertThat(proposal.getItems()).extracting(i -> i.getTargetDate()).doesNotHaveDuplicates();
        assertThat(proposal.getItems()).extracting(i -> i.getTargetDate()).contains(start, end);

        // 확정: 실행 항목도 날짜별 7개.
        PlanVersion plan = planConfirmService.confirm(userId(), proposal.getProposalId(),
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "매일 영어").build());
        List<ExecutionItem> created = planVersionService.findItems(userId(), plan.getPlanVersionId());
        assertThat(created).hasSize(7);
        assertThat(created).extracting(ExecutionItem::getScheduledDate).doesNotHaveDuplicates();
        assertThat(created).allSatisfy(i -> assertThat(i.getPlacementType()).isEqualTo(PlacementType.DATE_ONLY));

        // 셋째 날은 하루 종일 다른 일정으로 막는다.
        LocalDate blocked = start.plusDays(2);
        insertTimeFixed(TITLE_PREFIX + "종일 행사", blocked, blocked.atTime(9, 0), blocked.atTime(23, 0));

        PlanPlacementResponse placed = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        // 배치: 6개는 제 날짜 안에서 시각을 얻고, 막힌 날의 1개는 다른 날로 옮기지 않고 이유와 함께 남는다.
        assertThat(placed.getPlaced()).hasSize(6);
        assertThat(placed.getPlaced()).allSatisfy(p -> {
            ExecutionItem origin = created.stream().filter(i -> i.getExecutionItemId().equals(p.getExecutionItemId()))
                    .findFirst().orElseThrow();
            assertThat(p.getScheduledDate()).as("날짜가 정해진 항목은 그 날 안에서만 배치된다").isEqualTo(origin.getScheduledDate());
        });
        assertThat(placed.getUnplaced()).hasSize(1);
        assertThat(placed.getUnplaced().get(0).getScheduledDate()).isEqualTo(blocked);
        assertThat(placed.getUnplaced().get(0).getReason())
                .contains(blocked.getMonthValue() + "/" + blocked.getDayOfMonth() + "에 남는 시간이 없어요");
        List<ExecutionItem> after = planVersionService.findItems(userId(), plan.getPlanVersionId());
        assertThat(after).as("횟수를 줄이지 않는다").hasSize(7);
        assertThat(after).filteredOn(i -> i.getScheduledDate().equals(blocked))
                .allSatisfy(i -> assertThat(i.getPlacementType()).isEqualTo(PlacementType.DATE_ONLY));
        assertThat(after).filteredOn(i -> !i.getScheduledDate().equals(blocked))
                .allSatisfy(i -> assertThat(i.getPlacementType()).isEqualTo(PlacementType.TIME_FIXED));
    }

    // ===== fixture =====

    private void insertTimeFixed(String title, LocalDate date, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        ExecutionItem item = ExecutionItem.builder()
                .userId(userId()).title(title)
                .placementType(PlacementType.TIME_FIXED)
                .scheduledDate(date).scheduledStartAt(from).scheduledEndAt(to)
                .expectedMinutes(840)
                .status(ExecutionStatus.PLANNED).priority(ExecutionPriority.MUST)
                .orderIndex(0).originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false).version(0L).isDeleted(false)
                .build();
        executionItemMapper.insert(item);
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
