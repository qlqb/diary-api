package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 폴러. 틱마다 (1) 기존 자료·링크를 idempotent하게 등록하고 (2) 빈 스레드 수만큼 선점해 실행기에 넘긴다.
 *
 * <p>모델이 설정돼 있지 않거나 인증 실패 쿨다운 중이면 선점하지 않는다 — 작업은 QUEUED로 남고
 * 상태 화면이 "서비스 사용 불가"로 말한다. 처리는 이 클래스가 아니라 {@link MaterialAnalysisJobRunner}가 한다.
 */
@Slf4j
@Component
public class MaterialAnalysisScheduler {

    private final MaterialAnalysisJobService jobService;
    private final MaterialAnalysisJobRunner runner;
    private final AiConsultationClient aiConsultationClient;
    private final ThreadPoolTaskExecutor executor;

    @Value("${material.analysis.worker.enabled:true}")
    private boolean enabled = true;

    @Value("${material.analysis.worker.concurrency:2}")
    private int concurrency = 2;

    @Value("${material.analysis.backlog.batch:20}")
    private int backlogBatch = 20;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> authCooldownUntil = new AtomicReference<>(Instant.EPOCH);
    private final String owner = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean warnedNotConfigured = false;

    public MaterialAnalysisScheduler(MaterialAnalysisJobService jobService,
                                     MaterialAnalysisJobRunner runner,
                                     AiConsultationClient aiConsultationClient,
                                     @Qualifier("materialAnalysisExecutor") ThreadPoolTaskExecutor executor) {
        this.jobService = jobService;
        this.runner = runner;
        this.aiConsultationClient = aiConsultationClient;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${material.analysis.worker.poll-ms:5000}", initialDelayString = "10000")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            // 등록은 모델이 없어도 한다 — 상태 화면이 "대기 중"을 보여줄 수 있어야 하고, 설정이
            // 생기면 바로 이어지기 때문이다.
            jobService.registerBacklog(backlogBatch);
            jobService.registerLinkBacklog(backlogBatch);
        } catch (Exception e) {
            log.warn("분석 작업 등록 실패: {}", e.getClass().getSimpleName(), e);
        }
        if (!aiConsultationClient.isConfigured()) {
            if (!warnedNotConfigured) {
                log.warn("자료 자동 분석: AI가 설정되지 않아 작업을 처리하지 않는다(등록만 유지)");
                warnedNotConfigured = true;
            }
            return;
        }
        if (Instant.now().isBefore(authCooldownUntil.get())) {
            return;
        }
        int free = concurrency - inFlight.get();
        if (free <= 0) {
            return;
        }
        for (MaterialAnalysisJob job : jobService.claimNext(owner, free)) {
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        MaterialAnalysisJobRunner.Result result = runner.run(job);
                        if (result == MaterialAnalysisJobRunner.Result.AUTH_FAILURE) {
                            authCooldownUntil.set(Instant.now().plusSeconds(runner.authCooldownSeconds()));
                            log.warn("자료 자동 분석: 인증 실패로 {}초 동안 새 작업을 선점하지 않는다",
                                    runner.authCooldownSeconds());
                        }
                    } catch (Exception e) {
                        log.error("분석 작업 실행 중 처리되지 않은 예외: jobId={}", job.getJobId(), e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                // 스레드가 다 찼다. 임대는 만료되면 다른 틱이 다시 잡는다.
                inFlight.decrementAndGet();
                log.info("실행기가 가득 차 작업을 되돌린다: jobId={}", job.getJobId());
            }
        }
    }

    /** 테스트·진단용. */
    public int inFlight() {
        return inFlight.get();
    }
}
