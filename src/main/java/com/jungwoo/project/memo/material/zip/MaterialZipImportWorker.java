package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.common.exception.BusinessException;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.MaterialExtractionService;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 확정된 항목 하나를 자료로 만든다. 폴러({@link MaterialZipImportScheduler})가 선점한 항목마다 부른다.
 *
 * <p>순서는 업로드와 같다: 압축에서 임시 파일로 풀기 → 자료 폴더로 저장 → 추출 → [트랜잭션: 자료 +
 * 연결 + 단위 + 항목 완료] → 커밋 뒤 자동 분석 등록. 실패해도 남는 것은 참조되지 않는 파일뿐이다.
 *
 * <p>한 항목의 실패가 다른 항목을 취소하지 않는다. 실패는 그 항목에만 기록되고 사용자가 그것만
 * 다시 시도할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialZipImportWorker {

    private final ZipImportMapper importMapper;
    private final ZipImportEntryMapper entryMapper;
    private final ZipImportTxService txService;
    private final ZipArchiveReader archiveReader;
    private final FileStorageService fileStorageService;
    private final MaterialExtractionService materialExtractionService;
    private final MaterialService materialService;
    private final MaterialZipImportService importService;
    private final MaterialAnalysisJobService analysisJobService;
    private final com.jungwoo.project.memo.material.batch.MaterialAnalysisBatchService batchService;

    /** @return 자료가 만들어졌으면 true */
    public boolean importEntry(ZipImportEntry entry) {
        ZipImport zipImport = importMapper.findByIdAndUserId(entry.getImportId(), entry.getUserId());
        if (zipImport == null || zipImport.getStoragePath() == null) {
            txService.failEntry(entry.getEntryId(), "E404_028", "원본 압축 파일이 없어 가져오지 못했어요");
            return false;
        }
        if (zipImport.getStatus() == ZipImportStatus.CANCELLED) {
            txService.failEntry(entry.getEntryId(), "E409_024", "취소된 가져오기예요");
            return false;
        }
        Path temp = null;
        try {
            temp = archiveReader.extractEntry(importService.archivePath(zipImport), entry.getEntryPath(),
                    tempDir(zipImport), importService.limits());
            createMaterial(zipImport, entry, temp);
            return true;
        } catch (ZipArchiveReader.ZipArchiveException e) {
            fail(entry, "E400_014", e.getMessage());
        } catch (BusinessException e) {
            fail(entry, e.getErrorCode().getCode(), e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            // 화면에 예외 이름을 그대로 내보내지 않는다 — 사용자가 할 수 있는 말로 바꾸고, 원인은 로그에 남긴다.
            log.warn("가져오기 저장 실패: entryId={}, path={}", entry.getEntryId(), entry.getEntryPath(), e);
            fail(entry, "E500_001", "자료를 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요");
        } catch (IllegalStateException e) {
            // 이미 다른 실행이 이 항목의 자료를 만들었다. 실패로 적지 않는다 — 항목은 이미 DONE이다.
            log.info("가져오기 항목이 이미 완료됨: entryId={}", entry.getEntryId());
        } catch (Exception e) {
            log.warn("가져오기 실패: entryId={}, path={}", entry.getEntryId(), entry.getEntryPath(), e);
            fail(entry, "E500_001", "파일을 가져오지 못했어요. 잠시 뒤 다시 시도해 주세요");
        } finally {
            deleteQuietly(temp);
        }
        return false;
    }

    private void createMaterial(ZipImport zipImport, ZipImportEntry entry, Path temp) {
        FileStorageService.StoredFile stored =
                fileStorageService.storeFile(zipImport.getUserId(), entry.getDisplayName(), temp);
        Path savedPath = fileStorageService.resolve(stored.storagePath());
        MaterialExtractionService.Outcome result = materialExtractionService.extract(
                savedPath, stored.extension(), zipImport.getUserId(), stored.fileHash());

        CourseMaterial material = materialService.newMaterial(zipImport.getUserId(), entry.getDisplayName(),
                stored, sizeOf(savedPath, entry), result);
        // 출처: 어느 압축의 어느 경로에서 왔는지. 파일 저장 경로에는 쓰지 않는다.
        material.setSourceArchiveName(zipImport.getOriginalFilename());
        material.setSourceEntryPath(entry.getEntryPath());

        CourseMaterial saved;
        try {
            saved = txService.completeEntry(entry, material, zipImport.getCourseId(),
                    zipImport.getMaterialType(), result.units());
        } catch (RuntimeException e) {
            // DB에 남지 못했으면 방금 저장한 파일은 아무도 참조하지 않는다. 지우고 예외를 올린다.
            fileStorageService.deleteQuietly(null, stored.storagePath());
            throw e;
        }
        log.info("압축에서 자료 생성: userId={}, importId={}, entryId={}, materialId={}, status={}",
                zipImport.getUserId(), zipImport.getImportId(), entry.getEntryId(),
                saved.getMaterialId(), result.status());
        bindBatchItem(entry, saved.getMaterialId());

        if (result.success()) {
            // 커밋 뒤라 worker가 바로 집어도 자료 행이 보인다. 실패해도 backlog가 다시 등록한다.
            try {
                analysisJobService.enqueueContent(saved, MaterialAnalysisJobService.PRIORITY_NEW_UPLOAD);
            } catch (Exception e) {
                log.warn("가져온 자료의 분석 등록 실패(backlog가 다시 시도): materialId={}", saved.getMaterialId(), e);
            }
        }
    }

    private void fail(ZipImportEntry entry, String code, String message) {
        String text = message == null ? "가져오지 못했어요" : cut(message);
        txService.failEntry(entry.getEntryId(), code, text);
        try {
            batchService.failZipEntry(entry.getUserId(), entry.getEntryId(), text);
        } catch (Exception e) {
            log.warn("분석 묶음 자리에 실패를 적지 못했다: entryId={}", entry.getEntryId(), e);
        }
    }

    /**
     * 분석 묶음의 자리를 채운다. 자료는 이미 커밋됐다 — 여기서 실패해도 자료 생성을 되돌리지 않고
     * 로그만 남긴다. 업로드 경로의 묶음 연결(BatchUploadBinding)과 같은 규칙이다.
     */
    private void bindBatchItem(ZipImportEntry entry, Long materialId) {
        try {
            batchService.bindZipEntry(entry.getUserId(), entry.getEntryId(), materialId);
        } catch (Exception e) {
            log.warn("분석 묶음 자리를 채우지 못했다: entryId={}, materialId={}", entry.getEntryId(), materialId, e);
        }
    }

    private static String cut(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private long sizeOf(Path path, ZipImportEntry entry) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return entry.getSizeBytes() == null ? 0 : entry.getSizeBytes();
        }
    }

    private Path tempDir(ZipImport zipImport) {
        return fileStorageService.resolve(zipImport.getStoragePath()).getParent();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("임시 파일 삭제 실패: {}", path);
        }
    }

    /** 선점. 1행이 바뀌었을 때만 이 작업자가 그 항목을 가져간다. */
    public boolean claim(ZipImportEntry entry) {
        return entryMapper.claim(entry.getEntryId()) == 1;
    }
}
