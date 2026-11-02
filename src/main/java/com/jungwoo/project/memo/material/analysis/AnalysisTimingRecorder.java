package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.batch.MaterialAnalysisTimingMapper;
import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisTiming;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 끝난 작업의 소요 시간을 남긴다. 다음 번 "예상 3~5분"의 유일한 근거다.
 *
 * <p>기록 실패가 분석을 실패시키지 않는다 — 여기서 나는 예외는 삼킨다. 예상 시간은 있으면
 * 좋은 것이지 분석의 전제가 아니다.
 *
 * <p>청크 수는 <b>이번 실행에서 실제로 부른</b> 수를 쓴다. checkpoint 덕에 건너뛴 청크까지
 * 세면 "청크당 초"가 실제보다 짧게 나와 다음 추정이 낙관적이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTimingRecorder {

    private final MaterialAnalysisTimingMapper timingMapper;

    public void record(MaterialAnalysisJob job, AnalysisMetrics metrics, AnalysisJobStatus status, long totalMs) {
        if (metrics == null || status == null || metrics.calledChunks() <= 0) {
            // 모델을 한 번도 부르지 않은 실행(취소·본문 없음)은 표본이 아니다.
            return;
        }
        try {
            timingMapper.insertIgnore(MaterialAnalysisTiming.builder()
                    .userId(job.getUserId())
                    .jobId(job.getJobId())
                    .jobKind(job.getJobKind() == null ? "CONTENT" : job.getJobKind().name())
                    .extension(metrics.extension())
                    .sizeBytes(metrics.sizeBytes())
                    .charCount(metrics.charCount())
                    .unitCount(metrics.unitCount())
                    .chunkCount(metrics.calledChunks())
                    .queueWaitMs(queueWaitMs(job))
                    .extractMs(metrics.extractMs())
                    .modelMs(metrics.modelMs())
                    .persistMs(metrics.persistMs())
                    .totalMs(totalMs)
                    .outcome(outcomeOf(status))
                    .build());
        } catch (Exception e) {
            log.debug("분석 소요 기록 실패(무시): jobId={}, {}", job.getJobId(), e.getClass().getSimpleName());
        }
    }

    /** 등록부터 선점까지. 재시도로 미뤄진 시간도 여기 들어간다 — 화면의 "차례 대기"와 같은 뜻이다. */
    private static Long queueWaitMs(MaterialAnalysisJob job) {
        LocalDateTime queuedAt = job.getNextRunAt() != null ? job.getNextRunAt() : job.getCreatedAt();
        if (queuedAt == null) {
            return null;
        }
        long ms = Duration.between(queuedAt, LocalDateTime.now()).toMillis();
        return Math.max(0, ms);
    }

    private static String outcomeOf(AnalysisJobStatus status) {
        return switch (status) {
            case DONE -> "DONE";
            case PARTIAL -> "PARTIAL";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
    }
}
