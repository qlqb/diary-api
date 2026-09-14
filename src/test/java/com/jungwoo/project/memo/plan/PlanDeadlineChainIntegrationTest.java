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
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanPlacementResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * 마감이 제안에서 확정으로, 확정에서 배치까지 살아남는지 실제 DB에 대고 확인한다.
 *
 * <p>이 체인은 지금까지 확정에서 끊겨 있었다 — 제안 payload의 deadlineDate는 미리보기까지만
 * 쓰였고 execution_items에는 담을 컬럼이 없었으며, 롤링 배치는 항상 deadline=null로 돌았다.
 * 끊긴 자리가 다시 끊기면 증상이 "계획이 조금 이상하다" 정도로만 보이므로, 단계마다 값을
 * 직접 확인한다.
 *
 * <p>제약 자체(assignedEnd &gt; deadline이면 HARD)와 순서 제약은 SchedulingConstraintProviderTest가
 * 이미 검증한다. 여기서 보는 것은 <b>값이 그 제약까지 도달하는가</b>이다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanDeadlineChainIntegrationTest {

    private static final String TITLE_PREFIX = "PDC-";

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
    private PlanStrategyCodec strategyCodec;
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

    // ===== 확정: 제안의 마감이 실행 조각으로 넘어온다 =====

    @Test
    @DisplayName("DL-1: 확정하면 제안의 마감 시각이 execution_items.deadline_at에 남는다")
    void confirm_copiesDeadlineAtOntoTheExecutionItem() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDateTime deadline = start.plusDays(2).atTime(10, 0);

        Long proposalId = givenProposal(start, end,
                item("마감 있는 조각", 40, deadline, null),
                item("마감 없는 조각", 40, null, null));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "마감 복사").build());

        List<ExecutionItem> items = itemsOf(plan);
        assertThat(byTitle(items, "마감 있는 조각").getDeadlineAt())
                .as("이 값이 없으면 롤링 배치가 마감을 영영 모른다")
                .isEqualTo(deadline);
        assertThat(byTitle(items, "마감 없는 조각").getDeadlineAt())
                .as("근거 없는 마감을 만들어내지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("DL-6: 날짜 마감(deadlineDate)만 있으면 그 날의 다음날 00:00으로 옮긴다")
    void confirm_convertsDateOnlyDeadlineToTheEndOfThatDay() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDate deadlineDate = start.plusDays(3);

        Long proposalId = givenProposal(start, end, item("날짜 마감 조각", 40, null, deadlineDate));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "날짜 마감").build());

        assertThat(itemsOf(plan).get(0).getDeadlineAt())
                .as("'그 날까지'는 그 날 안에 끝내면 된다는 뜻이므로 경계는 다음날 00:00이다")
                .isEqualTo(deadlineDate.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("두 값이 다 있으면 시각(deadlineAt)이 이긴다 — 근거가 더 구체적인 쪽이다")
    void deadlineAtWinsOverDeadlineDate() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDateTime deadline = start.plusDays(2).atTime(10, 0);

        Long proposalId = givenProposal(start, end, item("둘 다 있는 조각", 40, deadline, start.plusDays(5)));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "우선순위").build());

        assertThat(itemsOf(plan).get(0).getDeadlineAt()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("DL-5: 확정 스냅샷에도 마감이 복사된다 — 나중에 마감이 바뀌어도 그때 판단이 남는다")
    void snapshotKeepsTheDeadline() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDateTime deadline = start.plusDays(2).atTime(10, 0);

        Long proposalId = givenProposal(start, end, item("스냅샷 대상", 40, deadline, null));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "스냅샷").build());

        List<PlanSnapshotItem> snapshot = snapshotCodec.fromJson(plan.getItemsSnapshot());
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).deadlineAt()).isEqualTo(deadline);

        // 저장된 JSON에서 다시 읽어도 같아야 한다 — 스냅샷은 문자열로만 남는다.
        PlanVersion reloaded = planVersionMapper.findByIdAndUserId(plan.getPlanVersionId(), userId());
        assertThat(snapshotCodec.fromJson(reloaded.getItemsSnapshot()).get(0).deadlineAt())
                .isEqualTo(deadline);
    }

    // ===== 배치: 마감이 Timefold까지 도달한다 =====

    @Test
    @DisplayName("DL-1: 배치된 조각은 마감을 넘겨 끝나지 않는다")
    void placement_neverEndsAfterTheDeadline() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDateTime deadline = start.plusDays(3).atTime(10, 0);

        Long proposalId = givenProposal(start, end,
                item("마감 있는 조각", 40, deadline, null),
                item("마감 없는 조각 A", 40, null, null),
                item("마감 없는 조각 B", 40, null, null));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "마감 배치").build());
        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        result.getPlaced().stream()
                .filter(p -> p.getTitle().equals(TITLE_PREFIX + "마감 있는 조각"))
                .forEach(p -> assertThat(p.getScheduledEndAt())
                        .as("마감을 넘겨 배치하면 '수업 전에 끝낸다'는 전제가 조용히 깨진다")
                        .isBeforeOrEqualTo(deadline));
    }

    @Test
    @DisplayName("DL-2: 마감 전에 넣을 자리가 없으면 억지로 넣지 않고 미배치로 남긴다")
    void placement_leavesTheItemUnplacedRatherThanMissingTheDeadline() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        LocalDate deadlineDay = start.plusDays(2);
        LocalDateTime deadline = deadlineDay.atTime(10, 0);

        Long proposalId = givenProposal(start, end, item("들어갈 자리 없는 조각", 60, deadline, null));
        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "자리 없음").build());

        // 마감까지의 모든 날을 통째로 막는다. 마감 이후에는 자리가 남아 있다 —
        // 그런데도 들어가면 안 된다는 것이 이 테스트의 요지다.
        for (LocalDate day = start; !day.isAfter(deadlineDay); day = day.plusDays(1)) {
            insertTimeFixed(TITLE_PREFIX + "막기 " + day, day, day.atTime(0, 0), day.atTime(23, 59));
        }

        PlanPlacementResponse result = planPlacementService.place(userId(), plan.getPlanVersionId(), start);

        assertThat(result.getPlaced()).isEmpty();
        assertThat(result.getUnplaced()).hasSize(1);
        assertThat(executionItemMapper.findByIdsForReview(userId(),
                List.of(result.getUnplaced().get(0).getExecutionItemId())).get(0).getPlacementType())
                .as("다음 창에서 다시 시도할 수 있도록 미배치로 남는다")
                .isEqualTo(PlacementType.UNSCHEDULED);
    }

    // ===== 판단 =====

    @Test
    @DisplayName("ST-1: 초안의 판단이 plan_versions.strategy_json으로 넘어온다")
    void confirm_copiesTheStrategyOntoThePlanVersion() {
        LocalDate start = today().plusDays(7);
        LocalDate end = start.plusDays(6);
        PlanStrategy strategy = new PlanStrategy(
                "다음 수업을 따라갈 수 있을 만큼 되돌리기",
                "밀린 두 주 중 다음 수업에 필요한 것만 고른다",
                StrategySource.NEW, null, List.of(1L),
                List.of(new PlanStrategy.CourseStrategy(36L, 1, "화요일 수업 전까지", "다음 수업이 가장 빠르다")),
                List.of(new PlanStrategy.TopicTreatment(217L, Treatment.FULL, 1, "아직 시작하지 않았다", List.of())),
                List.of("다음 수업 전 선수내용 완료"));

        Long proposalId = givenProposal(start, end, strategy, item("판단 있는 조각", 40, null, null));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "판단 복사").build());

        PlanStrategy saved = strategyCodec.fromJson(plan.getStrategyJson());
        assertThat(saved).isNotNull();
        assertThat(saved.strategySource()).isEqualTo(StrategySource.NEW);
        assertThat(saved.goal()).isEqualTo("다음 수업을 따라갈 수 있을 만큼 되돌리기");
        assertThat(saved.topics()).singleElement()
                .satisfies(t -> assertThat(t.treatment()).isEqualTo(Treatment.FULL));

        // DB에서 다시 읽어도 같아야 한다.
        PlanVersion reloaded = planVersionMapper.findByIdAndUserId(plan.getPlanVersionId(), userId());
        assertThat(strategyCodec.fromJson(reloaded.getStrategyJson()).planningRules())
                .containsExactly("다음 수업 전 선수내용 완료");
    }

    @Test
    @DisplayName("판단 없이 만든 초안은 strategy_json이 비어 있다 — 없는 판단을 지어내지 않는다")
    void confirm_leavesStrategyNullWhenThereWasNone() {
        LocalDate start = today().plusDays(7);
        Long proposalId = givenProposal(start, start.plusDays(6), item("판단 없는 조각", 40, null, null));

        PlanVersion plan = planConfirmService.confirm(userId(), proposalId,
                PlanConfirmRequest.builder().title(TITLE_PREFIX + "판단 없음").build());

        assertThat(plan.getStrategyJson()).isNull();
        assertThat(strategyCodec.fromJson(plan.getStrategyJson())).isNull();
    }

    // ===== fixture =====

    private ProposalItem item(String title, int minutes, LocalDateTime deadlineAt, LocalDate deadlineDate) {
        return new ProposalItem(TITLE_PREFIX + title, "이유", minutes, "SHOULD",
                PlacementType.UNSCHEDULED, null, null,
                null, deadlineDate, null, null, null, deadlineAt);
    }

    private Long givenProposal(LocalDate start, LocalDate end, ProposalItem... items) {
        return givenProposal(start, end, null, items);
    }

    private Long givenProposal(LocalDate start, LocalDate end, PlanStrategy strategy, ProposalItem... items) {
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId(), null, null, List.of(items), List.of(), start, List.of(), 30);
        createdProposalIds.add(proposal.getProposalId());
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId(), start, end,
                PlanIntensity.NORMAL, 600, strategyCodec.toJson(strategy), null);
        return proposal.getProposalId();
    }

    private List<ExecutionItem> itemsOf(PlanVersion plan) {
        List<Long> ids = snapshotCodec.fromJson(plan.getItemsSnapshot()).stream()
                .map(PlanSnapshotItem::executionItemId).toList();
        return executionItemMapper.findByIdsForReview(userId(), ids);
    }

    private ExecutionItem byTitle(List<ExecutionItem> items, String title) {
        return items.stream()
                .filter(i -> i.getTitle().equals(TITLE_PREFIX + title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("조각을 찾지 못했다: " + title));
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

    private void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
