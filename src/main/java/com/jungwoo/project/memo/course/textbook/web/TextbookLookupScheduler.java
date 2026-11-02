package com.jungwoo.project.memo.course.textbook.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 교재 조회 폴러. 틱마다 (1) 대기 중인 조회를 선점해 실행하고 (2) 목차를 확보한 조회의 자동 정리 상태를 다시 본다.
 *
 * <p>조회는 외부 웹 요청이라 자료 분석 실행기와 따로 작은 실행기를 쓴다(분석이 밀려도 교재 조회가 막히지 않고, 그 반대도).
 * 인증 실패 뒤에는 쿨다운 동안 선점하지 않는다. worker.enabled=false면 등록만 되고 처리는 멈춘다(테스트·일시 중지).
 */
@Slf4j
@Component
public class TextbookLookupScheduler implements DisposableBean {

    private final TextbookLookupMapper lookupMapper;
    private final TextbookLookupWorker worker;
    private final TextbookAutoTidy autoTidy;
    private final WebTocRefresher tocRefresher;
    private final ExecutorService executor;

    @Value("${textbook.lookup.worker.enabled:true}")
    private boolean enabled = true;

    @Value("${textbook.lookup.worker.concurrency:2}")
    private int concurrency = 2;

    @Value("${textbook.lookup.worker.lease-seconds:180}")
    private int leaseSeconds = 180;

    @Value("${textbook.lookup.auth-cooldown-seconds:1800}")
    private int authCooldownSeconds = 1800;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> authCooldownUntil = new AtomicReference<>(Instant.EPOCH);
    private final String owner = "textbook-" + UUID.randomUUID().toString().substring(0, 8);

    public TextbookLookupScheduler(TextbookLookupMapper lookupMapper, TextbookLookupWorker worker,
                                   TextbookAutoTidy autoTidy, WebTocRefresher tocRefresher) {
        this.lookupMapper = lookupMapper;
        this.worker = worker;
        this.autoTidy = autoTidy;
        this.tocRefresher = tocRefresher;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "textbook-lookup");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${textbook.lookup.worker.poll-ms:3000}", initialDelayString = "15000")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            tocRefresher.refreshOutdated();
        } catch (Exception e) {
            log.warn("목차 다시 읽기 실패: {}", e.getClass().getSimpleName(), e);
        }
        try {
            autoTidy.evaluate();
        } catch (Exception e) {
            log.warn("교재 목차 자동 정리 평가 실패: {}", e.getClass().getSimpleName(), e);
        }
        if (Instant.now().isBefore(authCooldownUntil.get())) {
            return;
        }
        int free = Math.min(2, concurrency) - inFlight.get();
        if (free <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TextbookLookup candidate : lookupMapper.findClaimable(now, free)) {
            if (lookupMapper.claim(candidate.getLookupId(), owner, now, now.plusSeconds(leaseSeconds)) != 1) {
                continue;
            }
            TextbookLookup job = lookupMapper.findById(candidate.getLookupId());
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        if (worker.run(job) == TextbookLookupWorker.Result.AUTH_FAILURE) {
                            authCooldownUntil.set(Instant.now().plusSeconds(authCooldownSeconds));
                            log.warn("교재 조회: 인증 실패로 {}초 동안 새 조회를 선점하지 않는다", authCooldownSeconds);
                        }
                    } catch (Exception e) {
                        log.error("교재 조회 실행 중 처리되지 않은 예외: lookupId={}", job.getLookupId(), e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                inFlight.decrementAndGet();
            }
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
