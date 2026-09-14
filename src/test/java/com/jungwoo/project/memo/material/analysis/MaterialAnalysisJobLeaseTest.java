package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 표의 선점·임대·토큰 대조를 실제 DB에 대고 검증한다(표 3·4·5의 DB 부분).
 *
 * <p>Mockito로는 "두 worker가 같은 행을 동시에 잡을 수 없다"와 "옛 임대의 결과 저장이 0행이 된다"를
 * 증명할 수 없다. 로컬 memo DB가 필요하며 CI에서는 -PexcludeDbTests로 빠진다.
 * docs/sql/2026-09-13-material-auto-analysis.sql 적용 뒤에만 통과한다.
 */
@SpringBootTest
class MaterialAnalysisJobLeaseTest {

    private static final long USER = 999_000_301L;
    private static final long MATERIAL = 999_300_001L;
    private static final String HASH = "f".repeat(64);

    @Autowired
    private MaterialAnalysisJobService jobService;
    @Autowired
    private MaterialAnalysisJobMapper jobMapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM material_analysis_jobs WHERE user_id = ?",
                    "DELETE FROM material_analysis_controls WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, USER);
                    ps.executeUpdate();
                }
            }
        }
    }

    private CourseMaterial material() {
        return CourseMaterial.builder().materialId(MATERIAL).userId(USER).fileHash(HASH)
                .extractionStatus(ExtractionStatus.SUCCESS).extractedText("text").build();
    }

    @Test
    void 같은_범위를_두_번_등록해도_작업은_하나다() {
        MaterialAnalysisJob first = jobService.enqueueContent(material(), 0);
        MaterialAnalysisJob second = jobService.enqueueContent(material(), 5);

        assertThat(first).isNotNull();
        assertThat(second.getJobId()).isEqualTo(first.getJobId());
        assertThat(jobMapper.findByUserId(USER)).hasSize(1);
    }

    @Test
    void 두_worker가_동시에_선점해도_한쪽만_잡는다() throws Exception {
        MaterialAnalysisJob job = jobService.enqueueContent(material(), 0);
        int workers = 6;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String owner = "w" + i;
            results.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                LocalDateTime now = LocalDateTime.now();
                return jobMapper.claim(job.getJobId(), owner, now, now.plusSeconds(60));
            }));
        }
        int won = 0;
        for (Future<Integer> f : results) {
            won += f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(won).isEqualTo(1);
        MaterialAnalysisJob after = jobMapper.findById(job.getJobId());
        assertThat(after.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(after.getLeaseToken()).isEqualTo(1L);
        assertThat(after.getAttempt()).isEqualTo(1);
    }

    @Test
    void 임대가_만료되면_다시_잡히고_옛_임대의_결과는_버려진다() throws Exception {
        MaterialAnalysisJob job = jobService.enqueueContent(material(), 0);
        LocalDateTime now = LocalDateTime.now();
        assertThat(jobMapper.claim(job.getJobId(), "old", now, now.plusSeconds(60))).isEqualTo(1);
        MaterialAnalysisJob oldLease = jobMapper.findById(job.getJobId());

        // 아직 임대 중이면 아무도 못 잡는다.
        assertThat(jobMapper.claim(job.getJobId(), "new", now, now.plusSeconds(60))).isZero();

        // 서버가 죽은 것처럼 임대를 과거로 돌린다.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE material_analysis_jobs SET lease_until = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE job_id = ?")) {
            ps.setLong(1, job.getJobId());
            ps.executeUpdate();
        }
        assertThat(jobMapper.claim(job.getJobId(), "new", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)))
                .isEqualTo(1);
        MaterialAnalysisJob newLease = jobMapper.findById(job.getJobId());
        assertThat(newLease.getLeaseToken()).isEqualTo(oldLease.getLeaseToken() + 1);

        // 늦게 돌아온 옛 worker: 진행·종료 저장이 전부 0행.
        assertThat(jobService.renew(oldLease)).isFalse();
        assertThat(jobService.progress(oldLease, 3, 1, "{\"completedChunks\":[0]}")).isFalse();
        assertThat(jobService.finish(oldLease, AnalysisJobStatus.DONE, null, "old", null)).isFalse();
        assertThat(jobMapper.findById(job.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);

        // 새 worker의 저장은 들어간다.
        assertThat(jobService.progress(newLease, 3, 2, "{\"completedChunks\":[0,1]}")).isTrue();
        assertThat(jobService.finish(newLease, AnalysisJobStatus.DONE, null, "new", null)).isTrue();
        MaterialAnalysisJob done = jobMapper.findById(job.getJobId());
        assertThat(done.getStatus()).isEqualTo(AnalysisJobStatus.DONE);
        assertThat(done.getCompletedChunks()).isEqualTo(2);
        assertThat(done.getErrorMessage()).isEqualTo("new");
    }

    @Test
    void 일시중지한_사용자의_작업은_선점_후보에서_빠지고_재개하면_돌아온다() {
        jobService.enqueueContent(material(), 0);
        assertThat(jobService.claimNext("w", 5)).extracting(MaterialAnalysisJob::getUserId).contains(USER);
        cleanUpQuiet();

        jobService.enqueueContent(material(), 0);
        jobService.pause(USER);
        assertThat(jobMapper.findByUserId(USER)).allMatch(j -> j.getStatus() == AnalysisJobStatus.PAUSED);
        assertThat(jobService.claimNext("w", 5)).noneMatch(j -> j.getUserId().equals(USER));

        jobService.resume(USER);
        assertThat(jobMapper.findByUserId(USER)).allMatch(j -> j.getStatus() == AnalysisJobStatus.QUEUED);
        assertThat(jobService.claimNext("w", 5)).extracting(MaterialAnalysisJob::getUserId).contains(USER);
    }

    @Test
    void 재시도_예약은_시각이_되기_전에는_잡히지_않는다() {
        MaterialAnalysisJob job = jobService.enqueueContent(material(), 0);
        LocalDateTime now = LocalDateTime.now();
        jobMapper.claim(job.getJobId(), "w", now, now.plusSeconds(60));
        MaterialAnalysisJob leased = jobMapper.findById(job.getJobId());
        assertThat(jobService.reschedule(leased, now.plusMinutes(30), "RATE_LIMIT", "429")).isTrue();

        assertThat(jobMapper.claim(job.getJobId(), "w2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60))).isZero();
        assertThat(jobMapper.claim(job.getJobId(), "w2", now.plusMinutes(31), now.plusMinutes(32))).isEqualTo(1);
        assertThat(jobMapper.findById(job.getJobId()).getAttempt()).isEqualTo(2);
    }

    @Test
    void 자료_삭제는_열린_작업을_취소하고_토큰을_바꿔_늦은_저장을_막는다() {
        MaterialAnalysisJob job = jobService.enqueueContent(material(), 0);
        LocalDateTime now = LocalDateTime.now();
        jobMapper.claim(job.getJobId(), "w", now, now.plusSeconds(60));
        MaterialAnalysisJob leased = jobMapper.findById(job.getJobId());

        jobService.cancelForMaterial(USER, MATERIAL);

        assertThat(jobMapper.findById(job.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.CANCELLED);
        assertThat(jobService.finish(leased, AnalysisJobStatus.DONE, null, "late", null)).isFalse();
        assertThat(jobMapper.findById(job.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.CANCELLED);
    }

    @Test
    void 선점_배치는_기존_자료_하나를_반드시_포함한다() {
        // 새 업로드(우선순위 0) 여럿과 기존 자료(10) 하나. 3개만 잡을 때 기존 자료가 굶지 않는다.
        for (long i = 0; i < 5; i++) {
            jobMapper.insertIgnore(MaterialAnalysisJob.builder().userId(USER).materialId(MATERIAL + 10 + i)
                    .courseId(0L).jobKind(AnalysisJobKind.CONTENT).fileHash(HASH).analysisVersion(1).priority(0)
                    .status(AnalysisJobStatus.QUEUED).maxAttempts(3).nextRunAt(LocalDateTime.now().minusSeconds(5)).build());
        }
        jobMapper.insertIgnore(MaterialAnalysisJob.builder().userId(USER).materialId(MATERIAL + 99)
                .courseId(0L).jobKind(AnalysisJobKind.CONTENT).fileHash(HASH).analysisVersion(1).priority(10)
                .status(AnalysisJobStatus.QUEUED).maxAttempts(3).nextRunAt(LocalDateTime.now().minusSeconds(5)).build());

        List<MaterialAnalysisJob> claimed = jobService.claimNext("w", 3);
        List<MaterialAnalysisJob> mine = claimed.stream().filter(j -> j.getUserId().equals(USER)).toList();
        assertThat(mine).hasSize(3);
        assertThat(mine).anyMatch(j -> j.getPriority() == 10);
    }

    private void cleanUpQuiet() {
        try {
            cleanUp();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void await(CountDownLatch latch) throws InterruptedException {
        latch.await(10, TimeUnit.SECONDS);
    }
}
