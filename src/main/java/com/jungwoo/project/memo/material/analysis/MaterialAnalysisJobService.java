package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.MaterialAnalysisControlMapper;
import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisControl;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 작업 표(material_analysis_jobs)의 등록·선점·진행·종료. 모델은 부르지 않는다.
 *
 * <p>등록은 idempotent다(UNIQUE + INSERT IGNORE). 선점은 UPDATE 한 문장이고 영향 행 1이어야
 * 실행한다. 진행·종료는 전부 lease_token을 대조한다 — 늦게 돌아온 이전 임대의 결과는 0행이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAnalysisJobService {

    /** 프롬프트·스키마가 의미 있게 바뀌면 올린다. 같은 해시라도 판이 다르면 다시 읽는다. */
    public static final int ANALYSIS_VERSION = 1;
    public static final int PRIORITY_NEW_UPLOAD = 0;
    public static final int PRIORITY_BACKFILL = 10;
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final MaterialAnalysisJobMapper jobMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialAnalysisControlMapper controlMapper;
    private final FileStorageService fileStorageService;

    @Value("${material.analysis.worker.lease-seconds:180}")
    private int leaseSeconds = 180;

    @Value("${material.analysis.daily-job-limit:60}")
    private int dailyJobLimit = 60;

    // ===== 등록 =====

    /** 업로드 직후. 추출이 실패한 자료는 등록하지 않는다 — 읽을 원문이 없다. */
    public MaterialAnalysisJob enqueueContent(CourseMaterial material, int priority) {
        String hash = hashOf(material);
        if (hash == null || material.getExtractedText() == null) {
            return null;
        }
        return enqueue(material.getUserId(), material.getMaterialId(), MaterialAnalysisJob.NO_COURSE,
                AnalysisJobKind.CONTENT, hash, priority);
    }

    public MaterialAnalysisJob enqueueLink(Long userId, Long materialId, Long courseId, String fileHash, int priority) {
        return enqueue(userId, materialId, courseId, AnalysisJobKind.LINK, fileHash, priority);
    }

    private MaterialAnalysisJob enqueue(Long userId, Long materialId, Long courseId, AnalysisJobKind kind,
                                        String fileHash, int priority) {
        MaterialAnalysisJob job = MaterialAnalysisJob.builder()
                .userId(userId).materialId(materialId).courseId(courseId).jobKind(kind)
                .fileHash(fileHash).analysisVersion(ANALYSIS_VERSION).priority(priority)
                .status(AnalysisJobStatus.QUEUED).maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .nextRunAt(LocalDateTime.now())
                .build();
        int inserted = jobMapper.insertIgnore(job);
        if (inserted == 0) {
            return jobMapper.findByScope(materialId, courseId, kind.name(), fileHash, ANALYSIS_VERSION);
        }
        log.info("분석 작업 등록: jobId={}, kind={}, materialId={}, courseId={}, priority={}",
                job.getJobId(), kind, materialId, courseId, priority);
        return job;
    }

    /**
     * 기존 자료 등록(백필). 해시가 없던 옛 자료는 파일에서, 파일이 없으면 extracted_text에서 해시를
     * 만들어 채운 뒤 등록한다. 한 번에 limit건만.
     *
     * @return 새로 등록한 수
     */
    public int registerBacklog(int limit) {
        int registered = 0;
        for (CourseMaterial material : courseMaterialMapper.findAnalysisBacklog(ANALYSIS_VERSION, limit)) {
            String hash = hashOf(material);
            if (hash == null) {
                continue;
            }
            MaterialAnalysisJob job = MaterialAnalysisJob.builder()
                    .userId(material.getUserId()).materialId(material.getMaterialId())
                    .courseId(MaterialAnalysisJob.NO_COURSE).jobKind(AnalysisJobKind.CONTENT)
                    .fileHash(hash).analysisVersion(ANALYSIS_VERSION).priority(PRIORITY_BACKFILL)
                    .status(AnalysisJobStatus.QUEUED).maxAttempts(DEFAULT_MAX_ATTEMPTS)
                    .nextRunAt(LocalDateTime.now())
                    .build();
            registered += jobMapper.insertIgnore(job);
        }
        if (registered > 0) {
            log.info("기존 자료 분석 등록: {}건", registered);
        }
        return registered;
    }

    /** CONTENT가 끝난 자료의 프로젝트 연결마다 LINK 작업을 만든다. */
    public int registerLinkBacklog(int limit) {
        int registered = 0;
        for (MaterialAnalysisJob content : jobMapper.findContentDoneWithoutLink(ANALYSIS_VERSION, limit)) {
            MaterialAnalysisJob job = MaterialAnalysisJob.builder()
                    .userId(content.getUserId()).materialId(content.getMaterialId())
                    .courseId(content.getCourseId()).jobKind(AnalysisJobKind.LINK)
                    .fileHash(content.getFileHash()).analysisVersion(ANALYSIS_VERSION)
                    .priority(content.getPriority() == null ? PRIORITY_BACKFILL : content.getPriority())
                    .status(AnalysisJobStatus.QUEUED).maxAttempts(DEFAULT_MAX_ATTEMPTS)
                    .nextRunAt(LocalDateTime.now())
                    .build();
            registered += jobMapper.insertIgnore(job);
        }
        return registered;
    }

    /**
     * 자료의 현재 해시. 없으면 파일(없으면 원문 텍스트)에서 계산해 채운다. 둘 다 없으면 null.
     */
    public String hashOf(CourseMaterial material) {
        if (material.getFileHash() != null) {
            return material.getFileHash();
        }
        String hash = null;
        if (material.getStoragePath() != null) {
            try {
                Path path = fileStorageService.resolve(material.getStoragePath());
                if (Files.isReadable(path)) {
                    hash = MaterialTextUnitService.sha256(path);
                }
            } catch (Exception ignored) {
                // 경로가 깨진 옛 자료. 텍스트 해시로 넘어간다.
            }
        }
        if (hash == null && material.getExtractedText() != null) {
            hash = sha256Text(material.getExtractedText());
        }
        if (hash != null && courseMaterialMapper.fillFileHashIfMissing(material.getMaterialId(), hash) > 0) {
            material.setFileHash(hash);
        }
        return hash;
    }

    // ===== 선점 · 진행 · 종료 =====

    /**
     * 실행할 작업을 최대 n개 선점한다. 우선순위 순이되, 대기 중인 기존 자료(priority ≥ 10)가 있으면
     * 그중 하나는 반드시 포함한다 — 새 업로드가 계속 들어와도 기존 자료가 굶지 않는다.
     * 사용자별 하루 시작 한도를 넘긴 작업은 건너뛴다(QUEUED로 남는다).
     */
    public List<MaterialAnalysisJob> claimNext(String owner, int n) {
        if (n <= 0) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, MaterialAnalysisJob> candidates = new LinkedHashMap<>();
        for (MaterialAnalysisJob job : jobMapper.findClaimable(now, null, n * 3)) {
            candidates.put(job.getJobId(), job);
        }
        boolean hasBackfill = candidates.values().stream().anyMatch(j -> j.getPriority() >= PRIORITY_BACKFILL);
        if (!hasBackfill) {
            for (MaterialAnalysisJob job : jobMapper.findClaimable(now, PRIORITY_BACKFILL, 1)) {
                candidates.put(job.getJobId(), job);
            }
        }
        List<MaterialAnalysisJob> ordered = new ArrayList<>(candidates.values());
        List<MaterialAnalysisJob> claimed = new ArrayList<>();
        boolean reservedBackfill = false;
        Map<Long, Integer> startedToday = new LinkedHashMap<>();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        for (MaterialAnalysisJob job : ordered) {
            if (claimed.size() >= n) {
                break;
            }
            boolean backfill = job.getPriority() >= PRIORITY_BACKFILL;
            // 마지막 자리는 backfill 몫으로 남긴다(후보에 backfill이 있고 아직 하나도 못 잡았을 때).
            if (!backfill && !reservedBackfill && claimed.size() == n - 1
                    && ordered.stream().anyMatch(j -> j.getPriority() >= PRIORITY_BACKFILL && !claimed.contains(j))) {
                continue;
            }
            int started = startedToday.computeIfAbsent(job.getUserId(),
                    uid -> jobMapper.countStartedSince(uid, dayStart));
            if (started >= dailyJobLimit) {
                continue;
            }
            int rows = jobMapper.claim(job.getJobId(), owner, now, now.plusSeconds(leaseSeconds));
            if (rows == 1) {
                MaterialAnalysisJob fresh = jobMapper.findById(job.getJobId());
                claimed.add(fresh);
                startedToday.put(job.getUserId(), started + 1);
                if (backfill) {
                    reservedBackfill = true;
                }
            }
        }
        return claimed;
    }

    /** 임대 연장. false면 임대를 잃었다 — 호출자는 즉시 멈추고 아무것도 저장하지 않는다. */
    public boolean renew(MaterialAnalysisJob job) {
        return jobMapper.renewLease(job.getJobId(), job.getLeaseToken(),
                LocalDateTime.now().plusSeconds(leaseSeconds)) == 1;
    }

    public boolean progress(MaterialAnalysisJob job, int totalChunks, int completedChunks, String checkpointJson) {
        return jobMapper.updateProgress(job.getJobId(), job.getLeaseToken(), totalChunks, completedChunks,
                checkpointJson) == 1;
    }

    public boolean finish(MaterialAnalysisJob job, AnalysisJobStatus status, String errorCode, String message,
                          Long resultRefId) {
        boolean ok = jobMapper.finish(job.getJobId(), job.getLeaseToken(), status.name(), errorCode,
                truncate(message, 500), resultRefId, LocalDateTime.now()) == 1;
        if (!ok) {
            log.info("분석 작업 종료 기록이 거부됨(임대가 바뀜): jobId={}, status={}", job.getJobId(), status);
        }
        return ok;
    }

    public boolean reschedule(MaterialAnalysisJob job, LocalDateTime nextRunAt, String errorCode, String message) {
        return jobMapper.reschedule(job.getJobId(), job.getLeaseToken(), nextRunAt, errorCode,
                truncate(message, 500)) == 1;
    }

    // ===== 사용자 제어 =====

    @Transactional
    public void pause(Long userId) {
        controlMapper.upsert(userId, true);
        jobMapper.pauseAllQueued(userId);
    }

    @Transactional
    public void resume(Long userId) {
        controlMapper.upsert(userId, false);
        jobMapper.resumeAllPaused(userId, LocalDateTime.now());
    }

    public boolean isPaused(Long userId) {
        MaterialAnalysisControl control = controlMapper.findByUserId(userId);
        return control != null && control.isPaused();
    }

    /** 다시 시도: 그 자료의 현재 해시 작업을 QUEUED로 되돌린다(없으면 새로 등록). 새 업로드 우선순위로. */
    @Transactional
    public MaterialAnalysisJob retryContent(CourseMaterial material) {
        String hash = hashOf(material);
        if (hash == null) {
            return null;
        }
        MaterialAnalysisJob existing = jobMapper.findByScope(material.getMaterialId(), MaterialAnalysisJob.NO_COURSE,
                AnalysisJobKind.CONTENT.name(), hash, ANALYSIS_VERSION);
        if (existing == null) {
            return enqueueContent(material, PRIORITY_NEW_UPLOAD);
        }
        jobMapper.requeue(existing.getJobId(), material.getUserId(), PRIORITY_NEW_UPLOAD, LocalDateTime.now());
        return jobMapper.findById(existing.getJobId());
    }

    /** 특정 (자료, 프로젝트)의 LINK를 다시. 변경안을 다시 만들고 싶을 때. */
    @Transactional
    public MaterialAnalysisJob retryLink(Long userId, Long materialId, Long courseId, String fileHash) {
        MaterialAnalysisJob existing = jobMapper.findByScope(materialId, courseId, AnalysisJobKind.LINK.name(),
                fileHash, ANALYSIS_VERSION);
        if (existing == null) {
            return enqueueLink(userId, materialId, courseId, fileHash, PRIORITY_NEW_UPLOAD);
        }
        jobMapper.requeue(existing.getJobId(), userId, PRIORITY_NEW_UPLOAD, LocalDateTime.now());
        return jobMapper.findById(existing.getJobId());
    }

    public void cancelForMaterial(Long userId, Long materialId) {
        int n = jobMapper.cancelOpenByMaterialId(materialId, userId);
        if (n > 0) {
            log.info("자료 삭제로 분석 작업 취소: materialId={}, {}건", materialId, n);
        }
    }

    public void cancelLinkJobs(Long userId, Long materialId, Long courseId) {
        jobMapper.cancelOpenLinkJobs(materialId, courseId, userId);
    }

    public List<MaterialAnalysisJob> findByUser(Long userId) {
        return jobMapper.findByUserId(userId);
    }

    public List<MaterialAnalysisJob> findByMaterials(Long userId, List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            return List.of();
        }
        return jobMapper.findByMaterialIds(materialIds, userId);
    }

    public MaterialAnalysisJob findById(Long jobId) {
        return jobMapper.findById(jobId);
    }

    static String sha256Text(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
