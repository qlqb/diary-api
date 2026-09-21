package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.batch.domain.BatchItemUploadState;
import com.jungwoo.project.memo.material.batch.domain.BatchStatus;
import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisBatch;
import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisBatchItem;
import com.jungwoo.project.memo.material.batch.dto.BatchEstimateResponse;
import com.jungwoo.project.memo.material.batch.dto.BatchRequests;
import com.jungwoo.project.memo.material.batch.dto.BatchResponse;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 업로드·분석 묶음. "이만큼 올리면 이 정도 걸려요 → 시작 → 여기까지 왔어요"의 서버 쪽.
 *
 * <p>왜 묶음이라는 것이 서버에 있어야 하는가: 진행 상태의 원본이 브라우저에 있으면 탭을
 * 닫는 순간 사라지고, 돌아왔을 때 무엇이 도는 중인지 알 수 없다. 그리고 "이번에 올린 5개"를
 * 서버가 알아야 분석 중에 2개를 더 올려도 5개짜리 진행률이 그대로 있는다.
 *
 * <p>진행 상태 자체는 여기서 만들지 않는다. {@link MaterialAnalysisStatusService}가 작업 표에서
 * 읽은 것을 자리에 붙여 접을 뿐이다 — 같은 사실을 두 곳에 저장하면 반드시 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAnalysisBatchService {

    /** 고르기만 하고 떠난 묶음을 치우는 기준. */
    static final int ABANDON_AFTER_HOURS = 24;
    /** 화면이 한 번에 복원할 열린 묶음 수. */
    static final int OPEN_BATCH_LIMIT = 5;

    private final MaterialAnalysisBatchMapper batchMapper;
    private final MaterialAnalysisTimingMapper timingMapper;
    private final AnalysisEstimator estimator;
    private final CourseService courseService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialAnalysisStatusService statusService;
    private final MaterialAnalysisJobService jobService;

    // ===== 시작 전 =====

    /** 묶음을 만들지 않고 예상만. 파일을 고르는 동안 목록이 바뀌므로 저장하지 않는다. */
    @Transactional(readOnly = true)
    public BatchEstimateResponse estimate(List<BatchRequests.Create.File> files) {
        List<AnalysisEstimator.FileEstimate> estimates = estimator.estimateFiles(files.stream()
                .map(f -> new AnalysisEstimator.StagedFile(f.getFilename(), f.getSizeBytes())).toList());
        return toEstimateResponse(estimates);
    }

    private BatchEstimateResponse toEstimateResponse(List<AnalysisEstimator.FileEstimate> estimates) {
        AnalysisEstimator.BatchEstimate batch = estimator.estimateBatch(estimates, timingMapper.countQueuedAhead());
        int unsupported = (int) estimates.stream().filter(e -> !e.supported()).count();
        return BatchEstimateResponse.builder()
                .minSeconds(batch.minSeconds()).maxSeconds(batch.maxSeconds()).basis(batch.basis())
                .estimableCount(batch.estimableCount()).unestimableCount(batch.unestimableCount())
                .unsupportedCount(unsupported).queueAheadSeconds(batch.queueAheadSeconds())
                .uploadTimeExcluded(true)
                .files(estimates.stream().map(e -> BatchEstimateResponse.File.builder()
                        .filename(e.filename()).extension(e.extension()).sizeBytes(e.sizeBytes())
                        .supported(e.supported()).estimable(e.estimable())
                        .minSeconds(e.minSeconds()).maxSeconds(e.maxSeconds()).reason(e.reason())
                        .build()).toList())
                .build();
    }

    /**
     * 묶음을 연다. 구성원이 여기서 정해지고 그 뒤로 바뀌지 않는다.
     *
     * <p>지원하지 않는 형식도 자리를 만든다 — 5개 골랐는데 3개만 보이면 무엇이 빠졌는지 알 수
     * 없다. 그 자리는 UNSUPPORTED로 시작하고 업로드 대상이 아니다.
     */
    @Transactional
    public BatchResponse create(Long userId, BatchRequests.Create request) {
        if (request.getCourseId() != null) {
            courseService.getOwned(userId, request.getCourseId());
        }
        List<AnalysisEstimator.FileEstimate> estimates = estimator.estimateFiles(request.getFiles().stream()
                .map(f -> new AnalysisEstimator.StagedFile(f.getFilename(), f.getSizeBytes())).toList());
        AnalysisEstimator.BatchEstimate total = estimator.estimateBatch(estimates, timingMapper.countQueuedAhead());

        MaterialAnalysisBatch batch = MaterialAnalysisBatch.builder()
                .userId(userId).courseId(request.getCourseId()).status(BatchStatus.STAGED)
                .itemCount(estimates.size())
                .estMinSeconds(total.minSeconds()).estMaxSeconds(total.maxSeconds()).estBasis(total.basis())
                .build();
        batchMapper.insert(batch);
        for (int i = 0; i < estimates.size(); i++) {
            AnalysisEstimator.FileEstimate estimate = estimates.get(i);
            batchMapper.insertItem(MaterialAnalysisBatchItem.builder()
                    .batchId(batch.getBatchId()).userId(userId).position(i)
                    .filename(cut(estimate.filename(), 255)).sizeBytes(estimate.sizeBytes())
                    .extension(cut(estimate.extension(), 20))
                    .uploadState(estimate.supported()
                            ? BatchItemUploadState.STAGED : BatchItemUploadState.UNSUPPORTED)
                    .estMinSeconds(estimate.minSeconds()).estMaxSeconds(estimate.maxSeconds())
                    .estBasis(estimate.basis())
                    .message(cut(estimate.reason(), 300))
                    .build());
        }
        log.info("분석 묶음 생성: batchId={}, userId={}, courseId={}, 자리={}",
                batch.getBatchId(), userId, request.getCourseId(), estimates.size());
        return get(userId, batch.getBatchId());
    }

    // ===== 업로드가 자리를 채운다 =====

    /**
     * 업로드 결과를 자리에 적는다. 업로드 경로가 커밋 뒤에 부른다.
     *
     * <p>자리를 잠근 채 쓴다 — 같은 자리에 두 업로드가 들어오면 뒤엣것은 무시한다. 자리가
     * 없거나 남의 것이면 조용히 지나간다(업로드 자체는 성공했다).
     */
    @Transactional
    public void bindUpload(Long userId, Long itemId, Long materialId) {
        MaterialAnalysisBatchItem item = batchMapper.findItemForUpdate(itemId, userId);
        if (item == null) {
            return;
        }
        if (item.getMaterialId() != null) {
            log.info("이미 채워진 묶음 자리에 다시 업로드가 들어왔다: itemId={}", itemId);
            return;
        }
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            return;
        }
        boolean readable = material.getExtractionStatus() == ExtractionStatus.SUCCESS;
        AnalysisEstimator.FileEstimate refined = estimator.refine(material.getOriginalFilename(),
                material.getSizeBytes(),
                material.getExtractedText() == null ? null : material.getExtractedText().length());
        batchMapper.updateItemUpload(itemId, userId,
                (readable ? BatchItemUploadState.UPLOADED : BatchItemUploadState.NO_TEXT).name(),
                material.getMaterialId(),
                readable ? null : cut(material.getExtractionError(), 300),
                refined.minSeconds(), refined.maxSeconds());
        touch(userId, item.getBatchId());
    }

    /** 업로드가 실패했다. 자리는 남기고 이유만 적는다. */
    @Transactional
    public void failUpload(Long userId, Long itemId, String message) {
        MaterialAnalysisBatchItem item = batchMapper.findItemForUpdate(itemId, userId);
        if (item == null || item.getMaterialId() != null) {
            return;
        }
        batchMapper.updateItemUpload(itemId, userId, BatchItemUploadState.UPLOAD_FAILED.name(), null,
                cut(message, 300), null, null);
        touch(userId, item.getBatchId());
    }

    /** 자리가 하나라도 채워지면 묶음은 더 이상 STAGED가 아니다. */
    private void touch(Long userId, Long batchId) {
        MaterialAnalysisBatch batch = batchMapper.findByIdAndUserId(batchId, userId);
        if (batch != null && batch.getStatus() == BatchStatus.STAGED) {
            batchMapper.updateStatus(batchId, userId, BatchStatus.UPLOADING.name(), LocalDateTime.now(), null);
        }
    }

    // ===== 조회 =====

    /**
     * 읽기지만 트랜잭션이 읽기 전용이 아니다 — 끝난 묶음의 상태를 이 자리에서 옮긴다
     * ({@link #toResponse} 참고). 별도 폴러를 두는 것보다 단순하고, 아무도 보지 않는 묶음의
     * 상태는 의미가 없다.
     */
    @Transactional
    public BatchResponse get(Long userId, Long batchId) {
        MaterialAnalysisBatch batch = batchMapper.findByIdAndUserId(batchId, userId);
        if (batch == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_ANALYSIS_BATCH_NOT_FOUND);
        }
        return toResponse(userId, batch, batchMapper.findItems(batchId, userId));
    }

    /** 화면에 돌아왔을 때 복원할 묶음들. 끝난 것은 오지 않는다(이 조회가 그렇게 표시한다). */
    @Transactional
    public List<BatchResponse> listOpen(Long userId, Long courseId) {
        List<BatchResponse> out = new ArrayList<>();
        for (MaterialAnalysisBatch batch : batchMapper.findOpenByUser(userId, courseId, OPEN_BATCH_LIMIT)) {
            out.add(toResponse(userId, batch, batchMapper.findItems(batch.getBatchId(), userId)));
        }
        return out;
    }

    /**
     * 진행 상태를 접는다. 상태의 원본은 작업 표이고 여기서는 자리에 붙여 세기만 한다.
     *
     * <p>끝난 묶음이면 status를 FINISHED로 옮긴다(조회가 부작용을 내는 유일한 곳이다 —
     * 별도 폴러를 두는 것보다 이쪽이 단순하고, 아무도 보지 않는 묶음의 상태는 의미가 없다).
     */
    private BatchResponse toResponse(Long userId, MaterialAnalysisBatch batch,
                                     List<MaterialAnalysisBatchItem> items) {
        List<Long> materialIds = items.stream().map(MaterialAnalysisBatchItem::getMaterialId)
                .filter(java.util.Objects::nonNull).toList();
        Map<Long, CourseMaterial> materials = new HashMap<>();
        Map<Long, MaterialAnalysisStatusResponse> statuses = new HashMap<>();
        if (!materialIds.isEmpty()) {
            List<CourseMaterial> found = courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(materialIds, userId);
            for (CourseMaterial material : found) {
                materials.put(material.getMaterialId(), material);
            }
            List<CourseMaterial> alive = found.stream().filter(m -> m.getStatus() == MaterialStatus.ACTIVE).toList();
            for (MaterialAnalysisStatusResponse status : statusService.statuses(userId, alive)) {
                statuses.put(status.getMaterialId(), status);
            }
        }

        List<BatchResponse.Item> rows = new ArrayList<>();
        double processed = 0;
        int done = 0, running = 0, waiting = 0, failed = 0, skipped = 0;
        int remainingMin = 0, remainingMax = 0;
        String waitingReason = null;
        for (MaterialAnalysisBatchItem item : items) {
            MaterialAnalysisStatusResponse status = item.getMaterialId() == null
                    ? null : statuses.get(item.getMaterialId());
            Stage stage = stageOf(item, materials.get(item.getMaterialId()), status);
            rows.add(BatchResponse.Item.builder()
                    .itemId(item.getItemId()).filename(item.getFilename()).sizeBytes(item.getSizeBytes())
                    .extension(item.getExtension()).materialId(item.getMaterialId())
                    .uploadState(item.getUploadState().name())
                    .stage(stage.name).stageLabel(stage.label)
                    .totalChunks(status == null ? null : status.getTotalChunks())
                    .completedChunks(status == null ? null : status.getCompletedChunks())
                    .estMinSeconds(item.getEstMinSeconds()).estMaxSeconds(item.getEstMaxSeconds())
                    .message(stage.message != null ? stage.message : item.getMessage())
                    .settled(stage.settled).retryable(stage.retryable)
                    .build());
            processed += stage.progress;
            if (stage.settled) {
                if (stage.success) {
                    done++;
                } else if (stage.skipped) {
                    skipped++;
                } else {
                    failed++;
                }
            } else {
                if ("ANALYZING".equals(stage.name)) {
                    running++;
                } else {
                    waiting++;
                }
                remainingMin += remainingSeconds(item.getEstMinSeconds(), stage.progress);
                remainingMax += remainingSeconds(item.getEstMaxSeconds(), stage.progress);
            }
            if (waitingReason == null && status != null && status.getWaitingReason() != null
                    && !"QUEUED".equals(status.getWaitingReason())) {
                waitingReason = status.getWaitingReason();
            }
        }

        int total = Math.max(1, items.size());
        int percent = (int) Math.min(100, Math.round(processed * 100.0 / total));
        boolean finished = rows.stream().allMatch(BatchResponse.Item::isSettled) && !items.isEmpty();
        BatchStatus status = finished ? BatchStatus.FINISHED
                : batch.getStatus() == BatchStatus.STAGED ? BatchStatus.STAGED : BatchStatus.ANALYZING;
        if (finished && batch.getStatus() != BatchStatus.FINISHED) {
            batchMapper.updateStatus(batch.getBatchId(), userId, BatchStatus.FINISHED.name(), null,
                    LocalDateTime.now());
        }
        MaterialAnalysisJobService.DailyLimitStatus limit = jobService.dailyLimitStatus(userId);
        return BatchResponse.builder()
                .batchId(batch.getBatchId()).courseId(batch.getCourseId()).status(status.name())
                .itemCount(items.size())
                .processedPercent(finished ? 100 : percent)
                .doneCount(done).runningCount(running).waitingCount(waiting)
                .failedCount(failed).skippedCount(skipped)
                .currentStage(finished ? null : running > 0 ? "내용 분석" : waiting > 0 ? "차례 대기" : null)
                .remainingMinSeconds(finished ? null : lane(remainingMin))
                .remainingMaxSeconds(finished ? null : lane(remainingMax))
                .estimateBasis(batch.getEstBasis())
                .uploadTimeExcluded(true)
                .waitingReason(finished ? null : waitingReason)
                .resumesAt("DAILY_LIMIT".equals(waitingReason) ? limit.resumesAt() : null)
                .createdAt(batch.getCreatedAt()).startedAt(batch.getStartedAt())
                .finishedAt(finished ? (batch.getFinishedAt() == null ? LocalDateTime.now() : batch.getFinishedAt())
                        : null)
                .items(rows)
                .build();
    }

    /** 남은 일감을 동시 처리 수로 나눈다. 0은 "곧"이 아니라 "모른다"에 가까워 1초로 바닥을 둔다. */
    private int lane(int seconds) {
        return seconds <= 0 ? 0 : Math.max(1, estimator.perLane(seconds));
    }

    private static int remainingSeconds(Integer estimate, double progress) {
        if (estimate == null) {
            return 0;
        }
        return (int) Math.round(estimate * Math.max(0, 1 - progress));
    }

    /**
     * 자리 하나의 단계.
     *
     * @param progress 처리 진행분(0~1). 분석 중인 자리는 청크 비율만큼만 세고 0.95를 넘기지 않는다 —
     *                 결과가 저장되기 전에 100%로 보이면 안 된다
     */
    private record Stage(String name, String label, double progress, boolean settled, boolean success,
                         boolean skipped, boolean retryable, String message) {
    }

    private Stage stageOf(MaterialAnalysisBatchItem item, CourseMaterial material,
                          MaterialAnalysisStatusResponse status) {
        switch (item.getUploadState()) {
            case STAGED -> {
                return new Stage("STAGED", "대기", 0, false, false, false, false, null);
            }
            case UPLOADING -> {
                return new Stage("UPLOADING", "올리는 중", 0, false, false, false, false, null);
            }
            case UNSUPPORTED -> {
                return new Stage("UNSUPPORTED", "분석할 수 없는 형식", 1, true, false, true, false, item.getMessage());
            }
            case UPLOAD_FAILED -> {
                return new Stage("UPLOAD_FAILED", "올리지 못함", 1, true, false, false, false, item.getMessage());
            }
            case NO_TEXT -> {
                return new Stage("NO_TEXT", "본문을 읽지 못함", 1, true, false, true, false, item.getMessage());
            }
            case ABANDONED -> {
                return new Stage("CANCELLED", "취소됨", 1, true, false, true, false, null);
            }
            default -> {
                // UPLOADED — 분석 상태가 말해 준다.
            }
        }
        if (material == null || material.getStatus() != MaterialStatus.ACTIVE) {
            return new Stage("CANCELLED", "자료가 지워짐", 1, true, false, true, false, null);
        }
        if (status == null) {
            return new Stage("QUEUED", "차례 대기", 0, false, false, false, false, null);
        }
        return switch (status.getState()) {
            case "DONE" -> new Stage("DONE", "완료", 1, true, true, false, false, null);
            case "PARTIAL" -> new Stage("PARTIAL", "일부 완료", 1, true, true, false, true, status.getMessage());
            case "RUNNING" -> new Stage("ANALYZING", "내용 분석", chunkProgress(status), false, false, false, false,
                    null);
            case "FAILED" -> new Stage("FAILED", "안 됨", 1, true, false, false, true, status.getMessage());
            case "UNAVAILABLE" -> new Stage("FAILED", "지금은 할 수 없음", 1, true, false, false, true,
                    status.getMessage());
            case "CANCELLED" -> new Stage("CANCELLED", "취소됨", 1, true, false, true, false, null);
            case "PAUSED" -> new Stage("PAUSED", "멈춤", 0, false, false, false, false, null);
            case "NO_TEXT" -> new Stage("NO_TEXT", "본문을 읽지 못함", 1, true, false, true, false,
                    status.getMessage());
            default -> new Stage("QUEUED", "차례 대기", 0, false, false, false, false, null);
        };
    }

    /** 청크 진행분. 저장이 끝나기 전에 1이 되지 않도록 0.95에서 멈춘다. */
    private static double chunkProgress(MaterialAnalysisStatusResponse status) {
        if (status.getTotalChunks() == null || status.getTotalChunks() <= 0) {
            return 0;
        }
        int completed = status.getCompletedChunks() == null ? 0 : status.getCompletedChunks();
        return Math.min(0.95, completed / (double) status.getTotalChunks());
    }

    // ===== 정리 =====

    /** 고르기만 하고 떠난 묶음. 폴러가 하루에 한 번쯤 부른다. */
    public int abandonStale() {
        return batchMapper.abandonStale(LocalDateTime.now().minusHours(ABANDON_AFTER_HOURS));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
