package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 정리 작업 폴러. 자료 분석 폴러와 같은 모양이고 같은 실행기를 쓴다.
 *
 * <p>정리는 사용자가 기다리는 작업이라 자료 분석보다 자주 본다. 다만 동시에 도는 수는 작게
 * 둔다 — 한 번 호출의 입력이 크고, 사용자 한 명이 프로젝트 여러 개를 동시에 정리하는 일은 거의 없다.
 */
@Slf4j
@Component
public class ProjectTidyScheduler {

    private final ProjectTidyMapper tidyMapper;
    private final ProjectTidyWorker worker;
    private final AiConsultationClient aiConsultationClient;
    private final ThreadPoolTaskExecutor executor;

    @Value("${project.tidy.worker.enabled:true}")
    private boolean enabled = true;

    @Value("${project.tidy.worker.concurrency:1}")
    private int concurrency = 1;

    @Value("${project.tidy.worker.lease-seconds:180}")
    private int leaseSeconds = 180;

    @Value("${project.tidy.auth-cooldown-seconds:1800}")
    private int authCooldownSeconds = 1800;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> authCooldownUntil = new AtomicReference<>(Instant.EPOCH);
    private final String owner = "tidy-" + UUID.randomUUID().toString().substring(0, 8);

    public ProjectTidyScheduler(ProjectTidyMapper tidyMapper, ProjectTidyWorker worker,
                                AiConsultationClient aiConsultationClient,
                                @Qualifier("materialAnalysisExecutor") ThreadPoolTaskExecutor executor) {
        this.tidyMapper = tidyMapper;
        this.worker = worker;
        this.aiConsultationClient = aiConsultationClient;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${project.tidy.worker.poll-ms:3000}", initialDelayString = "12000")
    public void tick() {
        if (!enabled || !aiConsultationClient.isConfigured()) {
            return;
        }
        if (Instant.now().isBefore(authCooldownUntil.get())) {
            return;
        }
        int free = concurrency - inFlight.get();
        if (free <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ProjectTidyJob candidate : tidyMapper.findClaimableJobs(now, free)) {
            if (inFlight.get() >= concurrency) {
                break;
            }
            if (tidyMapper.claimJob(candidate.getJobId(), owner, now, now.plusSeconds(leaseSeconds)) != 1) {
                continue;
            }
            ProjectTidyJob job = tidyMapper.findJobById(candidate.getJobId());
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        if (worker.run(job) == ProjectTidyWorker.Result.AUTH_FAILURE) {
                            authCooldownUntil.set(Instant.now().plusSeconds(authCooldownSeconds));
                            log.warn("프로젝트 정리: 인증 실패로 {}초 동안 새 작업을 선점하지 않는다", authCooldownSeconds);
                        }
                    } catch (Exception e) {
                        log.error("정리 작업 실행 중 처리되지 않은 예외: jobId={}", job.getJobId(), e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                // 스레드가 다 찼다. 임대가 만료되면 다음 틱이 다시 잡는다.
                inFlight.decrementAndGet();
                log.info("실행기가 가득 차 정리 작업을 되돌린다: jobId={}", job.getJobId());
            }
        }
    }

    /** 테스트·진단용. */
    public int inFlight() {
        return inFlight.get();
    }
}
