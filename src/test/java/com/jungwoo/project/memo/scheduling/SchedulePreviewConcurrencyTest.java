package com.jungwoo.project.memo.scheduling;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AiProposalSchedulePreview;
import com.jungwoo.project.memo.scheduling.dto.ScheduleItemOverride;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.service.SchedulePreviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 초안의 배치 미리보기를 <b>동시에</b> 두 번 요청하는 경우.
 *
 * <p>저장이 "조회 후 INSERT"라, 잠금 없이는 두 요청이 둘 다 "없다"를 보고 둘 다 INSERT해
 * uq_ai_proposal_schedule_previews_proposal에 걸려 한쪽이 500이 났다(초안 화면이 뜰 때 실제로
 * 그렇게 났다). 지금은 제안 행 잠금으로 직렬화한다.
 *
 * <p>여기서 보는 것: 둘 다 성공하고, 각자 <b>자기 입력</b>으로 계산한 결과를 받고(뒤 요청이 앞
 * 결과를 돌려받지 않는다), 저장된 행은 하나다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class SchedulePreviewConcurrencyTest {

    @Autowired private AiProposalService aiProposalService;
    @Autowired private AiProposalMapper aiProposalMapper;
    @Autowired private AiProposalItemMapper aiProposalItemMapper;
    @Autowired private SchedulePreviewService schedulePreviewService;
    @Autowired private SchedulePreviewMapper schedulePreviewMapper;
    @Autowired private DataSource dataSource;

    private Long userId;

    @BeforeEach
    void createTestUser() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (email, password_hash, nickname, role, status) "
                             + "VALUES (?, 'not-a-real-hash', 'spc-test', 'USER', 'ACTIVE')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "spc-test-" + System.nanoTime() + "@example.invalid");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                userId = keys.getLong(1);
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (userId == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM ai_proposal_schedule_previews WHERE user_id = ?",
                    "DELETE FROM ai_proposal_items WHERE user_id = ?",
                    "DELETE FROM ai_proposals WHERE user_id = ?",
                    "DELETE FROM users WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
        }
        userId = null;
    }

    @Test
    void twoConcurrentPreviews_bothSucceed_eachWithItsOwnInput_andOneRowIsStored() throws Exception {
        Long proposalId = givenPlaceableProposal();
        Long itemId = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId).get(0).getProposalItemId();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<SchedulePreviewResponse> thirty = () -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return schedulePreviewService.computePreview(userId, proposalId, withMinutes(itemId, 30));
            };
            Callable<SchedulePreviewResponse> sixty = () -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return schedulePreviewService.computePreview(userId, proposalId, withMinutes(itemId, 60));
            };
            Future<SchedulePreviewResponse> first = pool.submit(thirty);
            Future<SchedulePreviewResponse> second = pool.submit(sixty);

            SchedulePreviewResponse a = first.get(30, TimeUnit.SECONDS);
            SchedulePreviewResponse b = second.get(30, TimeUnit.SECONDS);

            assertThat(a.getPlacedItems()).hasSize(1);
            assertThat(b.getPlacedItems()).hasSize(1);
            assertThat(minutes(a)).as("30분 요청은 30분 배치를 받는다").isEqualTo(30);
            assertThat(minutes(b)).as("60분 요청은 60분 배치를 받는다").isEqualTo(60);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countStoredRows(proposalId)).isEqualTo(1);
        AiProposalSchedulePreview stored = schedulePreviewMapper.findByProposalIdAndUserId(proposalId, userId);
        assertThat(stored).isNotNull();
        // 저장된 행은 마지막에 커밋한 요청의 결과다 — 둘 중 하나여야 하고 둘이 섞인 값이면 안 된다.
        assertThat(minutes(schedulePreviewService.getStoredPreview(userId, proposalId))).isIn(30L, 60L);
    }

    @Test
    void aStoredPreview_isReturnedAsStored_andRecomputeReplacesIt() {
        Long proposalId = givenPlaceableProposal();
        Long itemId = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId).get(0).getProposalItemId();

        schedulePreviewService.computePreview(userId, proposalId, withMinutes(itemId, 30));
        assertThat(minutes(schedulePreviewService.getStoredPreview(userId, proposalId))).isEqualTo(30);

        schedulePreviewService.computePreview(userId, proposalId, withMinutes(itemId, 60));
        assertThat(minutes(schedulePreviewService.getStoredPreview(userId, proposalId))).isEqualTo(60);
        assertThat(countStoredRows(proposalId)).isEqualTo(1);
    }

    // ===== fixture =====

    private Long givenPlaceableProposal() {
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusDays(6);
        List<ProposalItem> items = List.of(new ProposalItem("SPC-항목", "이유", 30, "SHOULD",
                PlacementType.UNSCHEDULED, null, null, start, end, null, null, null));
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, null, null, items, List.of(), start, List.of(), 30, List.of());
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId, start, end,
                PlanIntensity.FOCUSED, 1080, null, null);
        return proposal.getProposalId();
    }

    private static SchedulePreviewRequest withMinutes(Long proposalItemId, int minutes) {
        ScheduleItemOverride override = new ScheduleItemOverride();
        override.setProposalItemId(proposalItemId);
        override.setExpectedMinutes(minutes);
        SchedulePreviewRequest request = new SchedulePreviewRequest();
        request.setItems(new ArrayList<>(List.of(override)));
        return request;
    }

    private static long minutes(SchedulePreviewResponse response) {
        var placed = response.getPlacedItems().get(0);
        return java.time.Duration.between(placed.getScheduledStartAt(), placed.getScheduledEndAt()).toMinutes();
    }

    private long countStoredRows(Long proposalId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM ai_proposal_schedule_previews WHERE proposal_id = ?")) {
            ps.setLong(1, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("조회 실패", e);
        }
    }
}
