package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파생 이동 적용의 동시성. <b>독립된 커넥션·트랜잭션</b>을 스레드마다 잡고, 순서는 래치로만
 * 제어한다 — sleep으로 순서를 맞추면 통과가 우연이 된다.
 *
 * <p>순차 테스트(원본을 미리 바꿔 두고 적용)로는 이걸 증명할 수 없다. 막으려는 것은 "검사할
 * 때는 맞았는데 INSERT 전에 바뀌는" 창이고, 그 창은 두 트랜잭션이 겹칠 때만 열린다.
 *
 * <p><b>docs/sql/2026-09-09-commitment-derived-travel.sql을 적용한 뒤에만 통과한다.</b>
 */
@SpringBootTest
class DerivedTravelConcurrencyTest {

    private static final Long TEST_USER_ID = 999_000_051L;
    private static final LocalDateTime SHIFT_START = LocalDateTime.of(2026, 9, 8, 18, 0);
    private static final LocalDateTime SHIFT_END = LocalDateTime.of(2026, 9, 8, 23, 0);

    @Autowired
    private CommitmentService commitmentService;

    @Autowired
    private CommitmentMapper commitmentMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM one_off_commitments WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER_ID);
            ps.executeUpdate();
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Commitment insertShift() {
        Commitment shift = Commitment.builder()
                .userId(TEST_USER_ID).title("근무").startAt(SHIFT_START).endAt(SHIFT_END)
                .sourceType(CommitmentSourceType.MANUAL).build();
        commitmentMapper.insert(shift);
        return shift;
    }

    private CommitmentCreateRequest travel(int minutes) {
        return CommitmentCreateRequest.builder()
                .title("근무 후 이동").startAt(SHIFT_END).endAt(SHIFT_END.plusMinutes(minutes)).build();
    }

    private CommitmentService.DerivedTravel origin(Commitment shift) {
        return new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                DerivedTravelRelation.AFTER_WORK, SHIFT_END);
    }

    private long liveDerivedCount(Long originId) {
        return commitmentService.findDerivedByOrigins(TEST_USER_ID, List.of(originId)).size();
    }

    /**
     * 적용이 원본 잠금을 먼저 잡으면, 그 사이의 원본 갱신은 적용 트랜잭션이 끝날 때까지 기다린다.
     *
     * <p>실행 순서: t1이 이동을 INSERT하고 커밋하지 않은 채 대기 → t2가 같은 근무를 UPDATE 시도
     * → t2가 끝나지 않았음을 확인 → t1 커밋 → t2 완료. "끝나지 않았음"은 짧은 대기로 확인한다.
     * 잠금이 없다면 t2는 즉시 끝나므로 이 방향의 단언은 느린 머신에서 더 안전해질 뿐이다.
     */
    @Test
    void 적용이_원본을_먼저_잠그면_원본_갱신이_기다린다() throws Exception {
        Commitment shift = insertShift();
        CountDownLatch travelInserted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);
        CountDownLatch updateDone = new CountDownLatch(1);
        AtomicInteger updated = new AtomicInteger(-1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> apply = pool.submit(() -> tx().execute(status -> {
                commitmentService.create(TEST_USER_ID, travel(60),
                        CommitmentSourceType.AI_SUGGESTION_APPROVED, origin(shift));
                travelInserted.countDown();
                try {
                    // 커밋하지 않은 채 원본 잠금을 들고 있는다.
                    releaseApply.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(travelInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> update = pool.submit(() -> tx().execute(status -> {
                updated.set(commitmentMapper.updateWithVersion(shift.getCommitmentId(), TEST_USER_ID, 0L,
                        "근무", SHIFT_START.plusHours(2), SHIFT_END.plusHours(2), null));
                updateDone.countDown();
                return null;
            }));

            // 잠금이 걸려 있으므로 아직 끝나지 않아야 한다.
            assertThat(updateDone.await(700, TimeUnit.MILLISECONDS)).isFalse();

            releaseApply.countDown();
            apply.get(15, TimeUnit.SECONDS);
            update.get(15, TimeUnit.SECONDS);

            assertThat(updateDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(updated.get()).isEqualTo(1);
            assertThat(liveDerivedCount(shift.getCommitmentId())).isEqualTo(1);
        } finally {
            releaseApply.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 원본 변경이 적용보다 먼저 커밋되면 적용은 409이고 이동은 만들어지지 않는다.
     *
     * <p>실행 순서: t1이 원본을 UPDATE하고 커밋을 미룬 채 대기 → t2가 적용을 시작(원본 잠금
     * 대기에 걸린다) → t1 커밋 → t2가 바뀐 원본을 읽고 거절.
     */
    @Test
    void 원본_변경이_먼저_커밋되면_적용은_거절되고_이동이_생기지_않는다() throws Exception {
        Commitment shift = insertShift();
        CountDownLatch originUpdated = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicReference<Throwable> applyFailure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = pool.submit(() -> tx().execute(status -> {
                commitmentMapper.updateWithVersion(shift.getCommitmentId(), TEST_USER_ID, 0L,
                        "근무", SHIFT_START.plusHours(2), SHIFT_END.plusHours(2), null);
                originUpdated.countDown();
                try {
                    releaseUpdate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(originUpdated.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> apply = pool.submit(() -> {
                try {
                    tx().execute(status -> commitmentService.create(TEST_USER_ID, travel(60),
                            CommitmentSourceType.AI_SUGGESTION_APPROVED, origin(shift)));
                } catch (Throwable t) {
                    applyFailure.set(t);
                }
                return null;
            });

            releaseUpdate.countDown();
            update.get(15, TimeUnit.SECONDS);
            apply.get(15, TimeUnit.SECONDS);

            assertThat(applyFailure.get()).isInstanceOf(ConflictException.class);
            assertThat(((ConflictException) applyFailure.get()).getErrorCode())
                    .isEqualTo(ErrorCode.DERIVED_COMMITMENT_ORIGIN_CHANGED);
            assertThat(liveDerivedCount(shift.getCommitmentId())).isZero();
        } finally {
            releaseUpdate.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 같은 근무·같은 방향을 두 스레드가 동시에 적용하면 하나만 저장된다.
     *
     * <p>둘 다 barrier에서 출발해 실제로 겹치게 만든다. 조회 후 검사만 있었다면 둘 다 "없음"을
     * 보고 둘 다 INSERT한다 — FOR UPDATE와 uk_commitments_derived가 그것을 막는다.
     */
    @Test
    void 같은_원본과_방향을_동시에_적용하면_하나만_저장된다() throws Exception {
        Commitment shift = insertShift();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    tx().execute(status -> commitmentService.create(TEST_USER_ID, travel(60),
                            CommitmentSourceType.AI_SUGGESTION_APPROVED, origin(shift)));
                    succeeded.incrementAndGet();
                } catch (ConflictException e) {
                    conflicted.incrementAndGet();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);

            assertThat(succeeded.get()).isEqualTo(1);
            assertThat(conflicted.get()).isEqualTo(1);
            assertThat(liveDerivedCount(shift.getCommitmentId())).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
