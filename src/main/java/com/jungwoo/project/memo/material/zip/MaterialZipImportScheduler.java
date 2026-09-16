package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가져오기 폴러. 틱마다 (1) 확정된 항목을 선점해 자료로 만들고, (2) 서버가 내려가 멈춘 항목을 되살리고,
 * (3) 남은 항목이 없는 가져오기를 마무리하고, (4) 기한이 지난 원본 압축을 지운다.
 *
 * <p>AI 분석 폴러와 같은 방식(DB 작업 표 + UPDATE 선점)이다. 외부 큐를 새로 들이지 않는다. 다만
 * 여기서는 모델을 부르지 않으므로 AI 설정·일시중지와 무관하게 돈다 — 자료를 만드는 일과 분석하는 일은
 * 다른 축이고, 분석이 멈춰 있어도 가져오기는 끝나야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialZipImportScheduler {

    private final ZipImportMapper importMapper;
    private final ZipImportEntryMapper entryMapper;
    private final ZipImportTxService txService;
    private final MaterialZipImportWorker worker;
    private final FileStorageService fileStorageService;

    @Value("${material.zip-import.worker.enabled:true}")
    private boolean enabled = true;

    @Value("${material.zip-import.worker.batch:3}")
    private int batch = 3;

    @Value("${material.zip-import.worker.stale-minutes:10}")
    private int staleMinutes = 10;

    @Value("${material.zip-import.worker.max-attempts:3}")
    private int maxAttempts = 3;

    @Scheduled(fixedDelayString = "${material.zip-import.worker.poll-ms:2000}", initialDelayString = "8000")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            recoverStale();
            importQueued();
            finishDone();
            cleanUpExpired();
        } catch (Exception e) {
            log.warn("압축 가져오기 폴러 실패: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** 확정된 항목을 하나씩. 한 틱에 batch개까지만 — 한 사용자의 큰 압축이 다른 일을 굶기지 않게. */
    private void importQueued() {
        List<ZipImportEntry> queued = entryMapper.findQueued(batch);
        for (ZipImportEntry entry : queued) {
            if (!worker.claim(entry)) {
                continue; // 다른 작업자가 먼저 가져갔다.
            }
            worker.importEntry(entry);
            txService.finishIfDone(entry.getImportId());
        }
    }

    /**
     * 선점한 채 서버가 내려간 항목을 되살린다. 자료가 이미 만들어진 항목(material_id)은 대상이 아니다 —
     * 자료 생성과 완료 기록이 같은 트랜잭션이라, material_id가 있으면 그 항목은 끝난 것이다.
     */
    private void recoverStale() {
        int requeued = entryMapper.requeueStale(LocalDateTime.now().minusMinutes(staleMinutes), maxAttempts);
        if (requeued > 0) {
            log.info("멈춰 있던 가져오기 항목 {}개를 다시 대기열에 넣었다", requeued);
        }
    }

    /** 작업자가 마지막 항목을 끝낸 직후를 놓쳤거나, 재시작으로 마무리가 밀린 가져오기. */
    private void finishDone() {
        for (ZipImport zipImport : importMapper.findImportingWithNoPendingEntries(batch)) {
            ZipImportStatus status = txService.finishIfDone(zipImport.getImportId());
            if (status != null && status.terminal() && zipImport.getStoragePath() != null) {
                // 끝났으면 원본 압축은 더 이상 필요 없다. 만들어진 자료는 각자 파일을 갖고 있다.
                fileStorageService.deleteQuietly(null, zipImport.getStoragePath());
                txService.clearStoragePath(zipImport.getImportId());
            }
        }
    }

    /** 고르지 않은 채 기한이 지난 가져오기. 원본만 지우고 상태를 EXPIRED로 남긴다(기록은 남는다). */
    private void cleanUpExpired() {
        for (ZipImport zipImport : importMapper.findExpired(LocalDateTime.now(), batch)) {
            fileStorageService.deleteQuietly(null, zipImport.getStoragePath());
            if (zipImport.getStatus().terminal()) {
                txService.clearStoragePath(zipImport.getImportId());
            } else {
                txService.expire(zipImport.getImportId());
            }
            log.info("기한이 지난 압축 원본 정리: importId={}", zipImport.getImportId());
        }
    }
}
