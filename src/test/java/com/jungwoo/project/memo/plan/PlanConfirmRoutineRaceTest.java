package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.routine.RoutineMapper;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정 도중 <b>반복 일정</b>(수업·루틴·보강)이 바뀌는 경우. 실제 로컬 MariaDB(REPEATABLE READ)에서
 * 독립된 두 트랜잭션으로 재현한다. 순서는 래치로만 제어한다 — 시간 지연으로 맞추면 통과가
 * 우연이 된다.
 *
 * <p>막으려는 실패: 확정 트랜잭션이 이미 스냅샷을 잡은 뒤에 커밋된 수업 시각 변경을 일반 조회가
 * 보지 못해, 겹치는 조각이 조용히 저장되는 것. 잠금 조회(expandForUpdate)가 없으면
 * {@link #routineChangedAfterTheConfirmsFirstRead_isStillSeenAndRejected}가 통과하지 못한다
 * (수정 전 코드에서 실제로 실패함을 확인했다 — 검증 기록 참고).
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanConfirmRoutineRaceTest {

    private static final String TITLE_PREFIX = "RRT-";

    @Autowired private AiProposalService aiProposalService;
    @Autowired private AiProposalMapper aiProposalMapper;
    @Autowired private AiProposalItemMapper aiProposalItemMapper;
    @Autowired private PlanConfirmService planConfirmService;
    @Autowired private RoutineService routineService;
    @Autowired private RoutineMapper routineMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private Long userId;
    /** 확정 항목이 놓일 시각. 계획 기간 안의 첫 날 19:00~19:40. */
    private LocalDate slotDate;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;

    @BeforeEach
    void createTestUser() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (email, password_hash, nickname, role, status) "
                             + "VALUES (?, 'not-a-real-hash', 'rrt-test', 'USER', 'ACTIVE')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "rrt-test-" + System.nanoTime() + "@example.invalid");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                userId = keys.getLong(1);
            }
        }
        slotDate = LocalDate.now().plusDays(8);
        slotStart = slotDate.atTime(19, 0);
        slotEnd = slotStart.plusMinutes(40);
    }

    /** 이 사용자의 행만 지운다. FK 순서대로. */
    @AfterEach
    void cleanUp() throws Exception {
        if (userId == null) {
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
                    "DELETE e FROM routine_exceptions e JOIN routines r ON r.routine_id = e.routine_id WHERE r.user_id = ?",
                    "DELETE w FROM routine_weekdays w JOIN routines r ON r.routine_id = w.routine_id WHERE r.user_id = ?",
                    "DELETE FROM routines WHERE user_id = ?",
                    "DELETE FROM one_off_commitments WHERE user_id = ?",
                    "DELETE FROM users WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
        }
        userId = null;
    }

    // ===== 순차: 변경이 먼저 커밋됐으면 확정이 본다 =====

    @Test
    void aClassMovedOntoTheSlotBeforeConfirm_rejectsTheWholeConfirm() {
        RoutineResponse routine = givenClass(slotDate.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(2);

        // 수업 시각이 확정 항목 위로 옮겨진다(18:30~20:00).
        routineService.update(userId, routine.routineId(),
                classRequest(Set.of(slotDate.getDayOfWeek()), LocalTime.of(18, 30), LocalTime.of(20, 0)));

        assertRejected(proposal);
        assertThat(countExecutionItems()).isZero();
        assertThat(countPlanVersions()).isZero();
    }

    @Test
    void aWeekdayAddedToAClass_thatNowCoversTheSlot_isRejected() {
        DayOfWeek other = slotDate.plusDays(1).getDayOfWeek();
        RoutineResponse routine = givenClass(other, LocalTime.of(18, 0), LocalTime.of(21, 0));
        Proposal proposal = givenProposal(1);

        // 요일만 추가된다 — 본체 시각은 그대로인데 이제 그 날에도 수업이 있다.
        routineService.update(userId, routine.routineId(),
                classRequest(Set.of(other, slotDate.getDayOfWeek()), LocalTime.of(18, 0), LocalTime.of(21, 0)));

        assertRejected(proposal);
        assertThat(countExecutionItems()).isZero();
    }

    @Test
    void aMakeUpClassMovedOntoTheSlot_isRejected() {
        // 원래 수업은 슬롯 이틀 뒤 요일이라 겹치지 않는다.
        LocalDate original = slotDate.plusDays(2);
        RoutineResponse routine = givenClass(original.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(1);

        RoutineExceptionSaveRequest moved = new RoutineExceptionSaveRequest(
                original, RoutineExceptionType.MOVED, slotDate, LocalTime.of(18, 30), LocalTime.of(20, 0),
                null, null);
        routineService.addException(userId, routine.routineId(), moved);

        assertRejected(proposal);
        assertThat(countExecutionItems()).isZero();
    }

    @Test
    void aBrandNewRoutineOnTheSlot_isRejected() {
        Proposal proposal = givenProposal(1);
        givenClass(slotDate.getDayOfWeek(), LocalTime.of(18, 30), LocalTime.of(20, 0));

        assertRejected(proposal);
        assertThat(countExecutionItems()).isZero();
    }

    @Test
    void withoutAnOverlap_theConfirmSucceeds() {
        givenClass(slotDate.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(1);

        PlanVersion plan = planConfirmService.confirm(userId, proposal.proposalId(), request(proposal));

        assertThat(plan.getPlanVersionId()).isNotNull();
        assertThat(countExecutionItems()).isEqualTo(1);
    }

    // ===== 경쟁 =====

    /**
     * 확정 트랜잭션이 첫 일반 조회로 스냅샷을 잡은 <b>뒤에</b> 수업 시각 변경이 커밋된다.
     *
     * <p>REPEATABLE READ에서 일반 조회는 그 스냅샷을 계속 보므로, 잠금 조회 없이는 확정이 옛
     * 시간표를 검사하고 겹치는 조각을 저장한다. 잠금 조회는 최신 커밋을 읽어 거절한다.
     */
    @Test
    void routineChangedAfterTheConfirmsFirstRead_isStillSeenAndRejected() throws Exception {
        RoutineResponse routine = givenClass(slotDate.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(1);
        PlanConfirmRequest request = request(proposal);

        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch routineCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirm = pool.submit(() -> {
                try {
                    // 확정과 같은 트랜잭션 안에서 먼저 일반 조회를 한 번 한다 — 스냅샷이 여기서 잡힌다.
                    new TransactionTemplate(transactionManager).execute(status -> {
                        aiProposalMapper.findByIdAndUserId(proposal.proposalId(), userId);
                        assertThat(routineMapper.findAllByUserId(userId)).hasSize(1);
                        snapshotTaken.countDown();
                        await(routineCommitted);
                        // REQUIRED라 위 트랜잭션에 참여한다.
                        planConfirmService.confirm(userId, proposal.proposalId(), request);
                        return null;
                    });
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            assertThat(snapshotTaken.await(10, TimeUnit.SECONDS)).isTrue();

            // 다른 연결에서 수업을 확정 항목 위로 옮기고 커밋한다.
            routineService.update(userId, routine.routineId(),
                    classRequest(Set.of(slotDate.getDayOfWeek()), LocalTime.of(18, 30), LocalTime.of(20, 0)));
            routineCommitted.countDown();

            confirm.get(20, TimeUnit.SECONDS);
        } finally {
            routineCommitted.countDown();
            pool.shutdownNow();
        }

        assertThat(outcome.get()).as("스냅샷 뒤에 커밋된 시간표 변경을 보고 거절해야 한다")
                .isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) outcome.get()).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT);
        assertThat(countExecutionItems()).isZero();
        assertThat(countPlanVersions()).isZero();
    }

    /**
     * 수업 시각 변경이 아직 커밋되지 않은 채 잠금을 들고 있으면 확정은 기다렸다가, 커밋된 뒤
     * 그 변경을 보고 거절한다.
     */
    @Test
    void anUncommittedRoutineChange_blocksTheConfirm_untilItCommits_thenRejects() throws Exception {
        RoutineResponse routine = givenClass(slotDate.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(1);

        runRaceAgainstUncommittedWrite(proposal, () -> routineService.update(userId, routine.routineId(),
                classRequest(Set.of(slotDate.getDayOfWeek()), LocalTime.of(18, 30), LocalTime.of(20, 0))));
    }

    /**
     * 새 루틴 INSERT는 어떤 기존 행도 잠그지 않는다. 확정의 잠금 조회가 (user_id, is_deleted)
     * 인덱스 범위를 훑으므로, 커밋되지 않은 INSERT가 있으면 확정이 기다리고 커밋 뒤 그것을 본다.
     */
    @Test
    void anUncommittedNewRoutineOnTheSlot_blocksTheConfirm_untilItCommits_thenRejects() throws Exception {
        Proposal proposal = givenProposal(1);

        runRaceAgainstUncommittedWrite(proposal, () -> routineService.create(userId,
                classRequest(Set.of(slotDate.getDayOfWeek()), LocalTime.of(18, 30), LocalTime.of(20, 0))));
    }

    /**
     * 확정이 먼저 잠금을 잡았으면 뒤따르는 루틴 변경은 확정이 끝날 때까지 기다린다. 확정은
     * 겹침 없이 성립하고, 변경은 그 뒤에 기존 정책대로 저장된다 — 여기서 막지 않는다.
     */
    @Test
    void aRoutineChangeArrivingWhileTheConfirmHoldsTheLocks_waits_andBothComplete() throws Exception {
        RoutineResponse routine = givenClass(slotDate.getDayOfWeek(), LocalTime.of(10, 0), LocalTime.of(13, 0));
        Proposal proposal = givenProposal(1);
        PlanConfirmRequest request = request(proposal);

        CountDownLatch locksHeld = new CountDownLatch(1);
        CountDownLatch releaseConfirm = new CountDownLatch(1);
        AtomicReference<Throwable> confirmOutcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirm = pool.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        planConfirmService.confirm(userId, proposal.proposalId(), request);
                        locksHeld.countDown();
                        await(releaseConfirm); // 커밋하지 않은 채 잠금을 들고 있는다.
                        return null;
                    });
                } catch (Throwable t) {
                    confirmOutcome.set(t);
                }
            });
            assertThat(locksHeld.await(20, TimeUnit.SECONDS)).isTrue();

            Future<?> change = pool.submit(() -> routineService.update(userId, routine.routineId(),
                    classRequest(Set.of(slotDate.getDayOfWeek()), LocalTime.of(18, 30), LocalTime.of(20, 0))));
            assertThatThrownBy(() -> change.get(700, TimeUnit.MILLISECONDS))
                    .as("확정이 잠금을 들고 있는 동안 루틴 변경은 기다린다")
                    .isInstanceOf(TimeoutException.class);

            releaseConfirm.countDown();
            confirm.get(20, TimeUnit.SECONDS);
            change.get(20, TimeUnit.SECONDS);
        } finally {
            releaseConfirm.countDown();
            pool.shutdownNow();
        }

        assertThat(confirmOutcome.get()).isNull();
        assertThat(countExecutionItems()).isEqualTo(1);
        assertThat(routineMapper.findByIdAndUserId(routine.routineId(), userId).getStartTime())
                .isEqualTo(LocalTime.of(18, 30));
    }

    private void runRaceAgainstUncommittedWrite(Proposal proposal, Runnable write) throws Exception {
        PlanConfirmRequest request = request(proposal);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                write.run();
                written.countDown();
                await(releaseWrite); // 커밋하지 않은 채 들고 있는다.
                return null;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> confirm = pool.submit(() -> {
                try {
                    planConfirmService.confirm(userId, proposal.proposalId(), request);
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            assertThatThrownBy(() -> confirm.get(700, TimeUnit.MILLISECONDS))
                    .as("확정이 커밋되지 않은 시간표 변경을 기다린다")
                    .isInstanceOf(TimeoutException.class);

            releaseWrite.countDown();
            writer.get(20, TimeUnit.SECONDS);
            confirm.get(20, TimeUnit.SECONDS);
        } finally {
            releaseWrite.countDown();
            pool.shutdownNow();
        }

        assertThat(outcome.get()).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) outcome.get()).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT);
        assertThat(countExecutionItems()).isZero();
        assertThat(countPlanVersions()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ===== fixture =====

    private record Proposal(Long proposalId, List<AiProposalItem> items) {
    }

    private Proposal givenProposal(int itemCount) {
        LocalDate start = slotDate;
        LocalDate end = start.plusDays(6);
        List<ProposalItem> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new ProposalItem(TITLE_PREFIX + "항목" + i, "이유 " + i, 40, "SHOULD",
                    PlacementType.UNSCHEDULED, null, null, start, end, null, null, null));
        }
        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, null, null, items, List.of(), start, List.of(), 30, List.of());
        aiProposalMapper.updatePlanMetadata(proposal.getProposalId(), userId, start, end,
                PlanIntensity.FOCUSED, 1080, null, null);
        return new Proposal(proposal.getProposalId(),
                aiProposalItemMapper.findByProposalIdAndUserId(proposal.getProposalId(), userId));
    }

    /** "미리보기가 정한 시각"을 그대로 실은 확정 요청. 첫 항목은 슬롯, 나머지는 3시간 뒤. */
    private PlanConfirmRequest request(Proposal proposal) {
        List<AiProposalApplyRequest.EditedProposalItem> edits = new ArrayList<>();
        for (int i = 0; i < proposal.items().size(); i++) {
            AiProposalApplyRequest.EditedProposalItem edit = new AiProposalApplyRequest.EditedProposalItem();
            edit.setProposalItemId(proposal.items().get(i).getProposalItemId());
            edit.setPlacementType(PlacementType.TIME_FIXED);
            LocalDateTime start = slotStart.plusHours(3L * i);
            edit.setScheduledDate(start.toLocalDate());
            edit.setScheduledStartAt(start);
            edit.setScheduledEndAt(start.plusMinutes(40));
            edits.add(edit);
        }
        return PlanConfirmRequest.builder().title(TITLE_PREFIX + "확정").editedItems(edits).build();
    }

    private RoutineResponse givenClass(DayOfWeek day, LocalTime start, LocalTime end) {
        return routineService.create(userId, classRequest(Set.of(day), start, end));
    }

    private RoutineSaveRequest classRequest(Set<DayOfWeek> days, LocalTime start, LocalTime end) {
        return new RoutineSaveRequest(null, "수업", null, new LinkedHashSet<>(days), start, end,
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(120));
    }

    private void assertRejected(Proposal proposal) {
        assertThatThrownBy(() -> planConfirmService.confirm(userId, proposal.proposalId(), request(proposal)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT));
    }

    private long countExecutionItems() {
        return count("SELECT COUNT(*) FROM execution_items WHERE user_id = ? AND is_deleted = 0");
    }

    private long countPlanVersions() {
        return count("SELECT COUNT(*) FROM plan_versions WHERE user_id = ?");
    }

    private long count(String sql) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("조회 실패", e);
        }
    }
}
