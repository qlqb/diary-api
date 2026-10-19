package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 상담 사진 원본 정리(기본 1시간마다). 차단(접근 막기)과 물리 삭제를 나눈다 — 파일 삭제가 실패해도 다음 주기에 다시 지운다.
 * <ol>
 *   <li>만료 시각이 지났는데 차단 표시가 없는 사진 → 차단(EXPIRED). 원본 조회는 이 표시와 무관하게 만료 시각부터 이미 막혀 있다.</li>
 *   <li>차단됐는데(만료·사용자 삭제·자료 삭제) 파일을 아직 못 지운 사진 → 파일 삭제, 성공하면 purged 표시.</li>
 *   <li>자료가 되지 못한 업로드(글자 없음·실패·멈춘 처리)의 파일 → 삭제.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoOriginalCleanupScheduler {

    static final int BATCH = 50;

    private final CourseMaterialMapper courseMaterialMapper;
    private final ConsultPhotoUploadMapper uploadMapper;
    private final FileStorageService fileStorageService;

    /** 테스트는 끈다(빌드 설정). 꺼도 빈은 있어 정리 테스트가 {@link #runOnce}를 직접 부른다. */
    @Value("${consult-photo.cleanup.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${consult-photo.cleanup.interval-ms:3600000}",
            initialDelayString = "${consult-photo.cleanup.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("상담 사진 원본 정리 실패(다음 주기에 다시)", e);
        }
    }

    /** @return 이번에 지운 파일 수 */
    public int runOnce(LocalDateTime now) {
        int expired = courseMaterialMapper.markExpiredOriginals(now, BATCH);
        int purged = 0;
        for (CourseMaterial m : courseMaterialMapper.findUnpurgedOriginals(BATCH)) {
            if (fileStorageService.deleteForPurge(m.getMaterialId(), m.getStoragePath())) {
                courseMaterialMapper.markOriginalPurged(m.getMaterialId(), now);
                purged++;
            }
        }
        uploadMapper.failAllStale(now.minusMinutes(ConsultPhotoService.STALE_MINUTES));
        int uploads = 0;
        for (ConsultPhotoUpload u : uploadMapper.findFilesToRemove(BATCH)) {
            if (fileStorageService.deleteForPurge(null, u.getStoragePath())) {
                uploadMapper.markFileRemoved(u.getUploadId(), now);
                uploads++;
            }
        }
        if (expired + purged + uploads > 0) {
            log.info("상담 사진 원본 정리: 만료 차단 {}건, 원본 삭제 {}건, 남은 업로드 파일 삭제 {}건", expired, purged, uploads);
        }
        return purged + uploads;
    }
}
