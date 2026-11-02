package com.jungwoo.project.memo.material.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 자료 분석 worker의 실행기. 이 저장소에서 유일한 백그라운드 스레드 풀이다.
 *
 * <p>큐 용량을 0으로 둔다 — 대기열은 DB 작업 표가 이미 맡고 있고, 여기서 또 쌓으면 임대만 잡힌 채
 * 메모리에서 기다리는 작업이 생긴다. 스레드가 비어 있을 때만 선점하는 것이 스케줄러의 규칙이다.
 */
@Configuration
@EnableScheduling
public class MaterialAnalysisWorkerConfig {

    @Bean(name = "materialAnalysisExecutor")
    public ThreadPoolTaskExecutor materialAnalysisExecutor(
            @Value("${material.analysis.worker.concurrency:2}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("material-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
