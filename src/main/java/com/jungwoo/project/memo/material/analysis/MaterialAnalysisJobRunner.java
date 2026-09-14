package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 선점된 작업 하나를 끝까지 처리한다. 종류별 분석기를 부르고, 실패를 분류해 재시도/종료를 정한다.
 *
 * <p>재시도 정책:
 * <ul>
 *   <li>AUTH — 작업 UNAVAILABLE, 스케줄러 전역 쿨다운. 재시도하지 않는다.</li>
 *   <li>RATE_LIMIT — 시도 횟수를 소모하지 않고 지수 백오프(2·4·8분…, 최대 1시간)로 미룬다.</li>
 *   <li>TRANSIENT/BAD_OUTPUT — max_attempts 안에서 30초·2분·8분 뒤 재시도. 넘으면 FAILED.
 *       성공한 청크는 checkpoint에 남아 다음 시도가 건너뛴다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialAnalysisJobRunner {

    public enum Result { COMPLETED, RESCHEDULED, FAILED, AUTH_FAILURE, LOST_LEASE }

    private final MaterialAnalysisJobService jobService;
    private final MaterialContentAnalyzer contentAnalyzer;
    private final TopicLinkAnalyzer linkAnalyzer;

    @Value("${material.analysis.auth-cooldown-seconds:1800}")
    private int authCooldownSeconds = 1800;

    public int authCooldownSeconds() {
        return authCooldownSeconds;
    }

    public Result run(MaterialAnalysisJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            AnalysisOutcome outcome = job.getJobKind() == AnalysisJobKind.LINK
                    ? linkAnalyzer.analyze(job)
                    : contentAnalyzer.analyze(job);
            if (outcome.leaseLost()) {
                return Result.LOST_LEASE;
            }
            jobService.finish(job, outcome.status(), outcome.errorCode(), outcome.message(), outcome.resultRefId());
            log.info("분석 작업 종료: jobId={}, kind={}, materialId={}, status={}, {}ms",
                    job.getJobId(), job.getJobKind(), job.getMaterialId(), outcome.status(),
                    System.currentTimeMillis() - startedAt);
            return Result.COMPLETED;
        } catch (AnalysisFailure failure) {
            return handleFailure(job, failure);
        } catch (Exception e) {
            return handleFailure(job, new AnalysisFailure(AnalysisFailureClassifier.classify(e),
                    e.getClass().getSimpleName() + ": " + safe(e.getMessage()), e));
        }
    }

    private Result handleFailure(MaterialAnalysisJob job, AnalysisFailure failure) {
        int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
        int max = job.getMaxAttempts() == null ? MaterialAnalysisJobService.DEFAULT_MAX_ATTEMPTS : job.getMaxAttempts();
        // 원문·토큰·파일명은 남기지 않는다. 진단에 필요한 것은 종류와 빈도다.
        log.warn("분석 작업 실패: jobId={}, kind={}, materialId={}, failure={}, attempt={}/{}",
                job.getJobId(), job.getJobKind(), job.getMaterialId(), failure.kind(), attempt, max);
        switch (failure.kind()) {
            case AUTH -> {
                jobService.finish(job, AnalysisJobStatus.UNAVAILABLE, "AUTH", failure.getMessage(), null);
                return Result.AUTH_FAILURE;
            }
            case RATE_LIMIT -> {
                long minutes = Math.min(60, 2L << Math.min(5, Math.max(0, attempt - 1)));
                jobService.reschedule(job, LocalDateTime.now().plusMinutes(minutes), "RATE_LIMIT", failure.getMessage());
                return Result.RESCHEDULED;
            }
            default -> {
                if (attempt >= max) {
                    jobService.finish(job, AnalysisJobStatus.FAILED, failure.kind().name(), failure.getMessage(), null);
                    return Result.FAILED;
                }
                long seconds = switch (attempt) {
                    case 1 -> 30;
                    case 2 -> 120;
                    default -> 480;
                };
                jobService.reschedule(job, LocalDateTime.now().plusSeconds(seconds), failure.kind().name(),
                        failure.getMessage());
                return Result.RESCHEDULED;
            }
        }
    }

    private static String safe(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
