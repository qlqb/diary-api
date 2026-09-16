package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import com.jungwoo.project.memo.material.zip.dto.ZipImportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 압축 파일 가져오기의 사용자 경로: 올리기·목록·확정·재시도·취소.
 *
 * <p>가져오기는 자료 업로드와 다른 일이다. 여기서는 "무엇이 들어 있는지" 보여주고 사용자가 고른
 * 것만 자료로 만든다. 압축 자체는 자료가 되지 않고, 안의 파일 각각이 독립된 자료가 된다 —
 * 폴더명으로 프로젝트나 학습 항목을 자동으로 만들지 않는다.
 *
 * <p>실제로 자료를 만드는 일은 {@link MaterialZipImportWorker}가 뒤에서 한다. 사용자가 화면을
 * 떠나거나 새로고침해도 진행되고, 다시 들어오면 이 서비스의 조회로 상태를 되찾는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialZipImportService {

    private final ZipImportMapper importMapper;
    private final ZipImportEntryMapper entryMapper;
    private final ZipArchiveReader archiveReader;
    private final ZipImportTxService txService;
    private final FileStorageService fileStorageService;
    private final CourseService courseService;

    @Value("${material.zip-import.max-archive-bytes:20971520}")
    private long maxArchiveBytes = 20L * 1024 * 1024;

    @Value("${material.zip-import.max-entry-bytes:20971520}")
    private long maxEntryBytes = 20L * 1024 * 1024;

    @Value("${material.zip-import.max-total-uncompressed-bytes:209715200}")
    private long maxTotalBytes = 200L * 1024 * 1024;

    @Value("${material.zip-import.max-entries:1000}")
    private int maxEntries = 1000;

    @Value("${material.zip-import.max-selectable:100}")
    private int maxSelectable = 100;

    @Value("${material.zip-import.retention-hours:24}")
    private int retentionHours = 24;

    ZipArchiveReader.Limits limits() {
        return new ZipArchiveReader.Limits(maxEntries, maxEntryBytes, maxTotalBytes, maxSelectable);
    }

    long maxArchiveBytes() {
        return maxArchiveBytes;
    }

    /**
     * 압축을 받아 목록을 만든다. 이 단계에서는 자료가 하나도 생기지 않고 분석도 시작하지 않는다.
     *
     * <p>목록 만들기는 헤더만 읽으므로 요청 안에서 끝난다. 오래 걸리는 것은 다음 단계(해제·추출)이고
     * 그건 작업자가 한다.
     */
    public ZipImportResponse create(Long userId, Long courseId, MaterialType materialType, MultipartFile file) {
        if (courseId != null) {
            requireActiveCourse(userId, courseId);
        }
        FileStorageService.StoredFile stored = fileStorageService.storeArchive(userId, file, maxArchiveBytes);
        ZipImport zipImport = ZipImport.builder()
                .userId(userId).courseId(courseId).materialType(materialType)
                .originalFilename(sanitize(file.getOriginalFilename()))
                .storagePath(stored.storagePath())
                .sizeBytes(file.getSize())
                .fileHash(stored.fileHash())
                .status(ZipImportStatus.PREPARING)
                .entryCount(0).selectableCount(0)
                .expiresAt(LocalDateTime.now().plusHours(retentionHours))
                .build();
        importMapper.insert(zipImport);

        ZipArchiveReader.Listing listing;
        try {
            listing = archiveReader.list(fileStorageService.resolve(stored.storagePath()), limits());
        } catch (ZipArchiveReader.ZipArchiveException e) {
            importMapper.updateStatus(zipImport.getImportId(), ZipImportStatus.FAILED, "E400_014", e.getMessage());
            fileStorageService.deleteQuietly(null, stored.storagePath());
            importMapper.clearStoragePath(zipImport.getImportId());
            throw new BadRequestException(ErrorCode.UNSUPPORTED_FILE_TYPE, e.getMessage());
        }
        txService.saveListing(zipImport, toRows(zipImport, listing), listing.selectableCount(),
                listing.warnings().isEmpty() ? null : cut(String.join(" · ", listing.warnings()), 500));
        log.info("압축 가져오기 준비: userId={}, importId={}, entries={}, selectable={}",
                userId, zipImport.getImportId(), listing.entries().size(), listing.selectableCount());
        return get(userId, zipImport.getImportId());
    }

    private List<ZipImportEntry> toRows(ZipImport zipImport, ZipArchiveReader.Listing listing) {
        List<ZipImportEntry> rows = new java.util.ArrayList<>();
        for (ZipArchiveReader.ArchiveEntry entry : listing.entries()) {
            rows.add(ZipImportEntry.builder()
                    .importId(zipImport.getImportId())
                    .userId(zipImport.getUserId())
                    .entryIndex(entry.index())
                    .entryPath(cut(entry.path(), 1000))
                    .displayName(cut(entry.displayName(), 255))
                    .extension(entry.extension())
                    .sizeBytes(entry.sizeBytes())
                    .supported(entry.supported())
                    .skipReason(cut(entry.skipReason(), 200))
                    .status(entry.supported() ? ZipEntryStatus.PENDING : ZipEntryStatus.UNSUPPORTED)
                    .attempt(0)
                    .build());
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public ZipImportResponse get(Long userId, Long importId) {
        ZipImport zipImport = owned(userId, importId);
        return ZipImportResponse.of(zipImport, entryMapper.findByImportId(importId, userId));
    }

    @Transactional(readOnly = true)
    public List<ZipImportResponse> listRecent(Long userId, int limit) {
        return importMapper.findRecentByUserId(userId, limit).stream()
                .map(i -> ZipImportResponse.of(i, entryMapper.findByImportId(i.getImportId(), userId)))
                .toList();
    }

    /**
     * 고른 항목을 확정한다. 여기서 바로 자료를 만들지 않고 대기열에만 넣는다 — 응답이 유실돼도,
     * 사용자가 화면을 닫아도, 같은 확정이 두 번 와도 결과가 같아야 하기 때문이다.
     */
    public ZipImportResponse confirm(Long userId, Long importId, List<Long> entryIds) {
        ZipImport zipImport = owned(userId, importId);
        if (zipImport.getStatus() != ZipImportStatus.READY && zipImport.getStatus() != ZipImportStatus.IMPORTING
                && zipImport.getStatus() != ZipImportStatus.PARTIAL) {
            throw new ConflictException(ErrorCode.ZIP_IMPORT_NOT_READY);
        }
        if (entryIds == null || entryIds.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE, "가져올 파일을 하나 이상 골라주세요");
        }
        if (entryIds.size() > maxSelectable) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE,
                    "한 번에 " + maxSelectable + "개까지 가져올 수 있어요");
        }
        if (zipImport.getCourseId() != null) {
            // 목록을 보는 동안 프로젝트가 지워지거나 보관됐을 수 있다. 잘못된 연결을 만들지 않는다.
            requireActiveCourse(userId, zipImport.getCourseId());
        }
        int queued = entryMapper.queueSelected(importId, userId, entryIds);
        if (queued > 0) {
            importMapper.updateStatus(importId, ZipImportStatus.IMPORTING, null, null);
        }
        log.info("압축 가져오기 확정: userId={}, importId={}, 요청={}, 대기열={}",
                userId, importId, entryIds.size(), queued);
        return get(userId, importId);
    }

    /** 실패한 항목 하나만 다시. 이미 자료가 된 항목은 대상이 아니다(삭제됐더라도 다시 만들지 않는다). */
    public ZipImportResponse retryEntry(Long userId, Long importId, Long entryId) {
        ZipImport zipImport = owned(userId, importId);
        ZipImportEntry entry = entryMapper.findByIdAndUserId(entryId, userId);
        if (entry == null || !entry.getImportId().equals(importId)) {
            throw new NotFoundException(ErrorCode.ZIP_IMPORT_ENTRY_NOT_FOUND);
        }
        if (entry.getStatus() != ZipEntryStatus.FAILED || entry.getMaterialId() != null) {
            throw new ConflictException(ErrorCode.ZIP_IMPORT_ENTRY_NOT_RETRYABLE);
        }
        if (zipImport.getStoragePath() == null) {
            throw new ConflictException(ErrorCode.ZIP_IMPORT_ARCHIVE_EXPIRED);
        }
        entryMapper.queueSelected(importId, userId, List.of(entryId));
        importMapper.updateStatus(importId, ZipImportStatus.IMPORTING, null, null);
        return get(userId, importId);
    }

    /** 아직 시작하지 않은 항목만 취소한다. 이미 만들어진 자료는 사용자 것이므로 건드리지 않는다. */
    public ZipImportResponse cancel(Long userId, Long importId) {
        ZipImport zipImport = owned(userId, importId);
        if (zipImport.getStatus().terminal()) {
            return get(userId, importId);
        }
        importMapper.updateStatus(importId, ZipImportStatus.CANCELLED, null, null);
        if (zipImport.getStoragePath() != null) {
            fileStorageService.deleteQuietly(null, zipImport.getStoragePath());
            importMapper.clearStoragePath(importId);
        }
        log.info("압축 가져오기 취소: userId={}, importId={}", userId, importId);
        return get(userId, importId);
    }

    ZipImport owned(Long userId, Long importId) {
        ZipImport zipImport = importMapper.findByIdAndUserId(importId, userId);
        if (zipImport == null) {
            throw new NotFoundException(ErrorCode.ZIP_IMPORT_NOT_FOUND);
        }
        return zipImport;
    }

    private void requireActiveCourse(Long userId, Long courseId) {
        Course course = courseService.getOwned(userId, courseId);
        if (course.getStatus() == CourseStatus.ARCHIVED) {
            throw new ConflictException(ErrorCode.COURSE_ARCHIVED);
        }
    }

    Path archivePath(ZipImport zipImport) {
        return fileStorageService.resolve(zipImport.getStoragePath());
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "압축파일.zip";
        }
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        return cut(base, 255);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
