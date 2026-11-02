package com.jungwoo.project.memo.material.zip.dto;

import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가져오기 한 건과 그 안의 파일들. 화면이 새로고침 뒤에도 이 응답만으로 상태를 복구한다.
 *
 * <p>status는 "자료가 만들어졌는가"이지 "분석이 끝났는가"가 아니다 — 분석 상태는 자료마다 따로
 * (자동 분석 상태 API) 본다.
 */
@Getter
@Builder
public class ZipImportResponse {

    private Long importId;
    private String originalFilename;
    private Long sizeBytes;
    private Long courseId;
    private MaterialType materialType;
    private ZipImportStatus status;
    private Integer entryCount;
    private Integer selectableCount;
    private Integer doneCount;
    private Integer failedCount;
    private Integer remainingCount;
    /** 원본 압축을 아직 보관 중인가. false면 재시도할 수 없다. */
    private boolean archiveAvailable;
    private LocalDateTime expiresAt;
    private String errorCode;
    private String message;
    private LocalDateTime createdAt;
    private List<ZipImportEntryResponse> entries;

    public static ZipImportResponse of(ZipImport zipImport, List<ZipImportEntry> entries) {
        return ZipImportResponse.builder()
                .importId(zipImport.getImportId())
                .originalFilename(zipImport.getOriginalFilename())
                .sizeBytes(zipImport.getSizeBytes())
                .courseId(zipImport.getCourseId())
                .materialType(zipImport.getMaterialType())
                .status(zipImport.getStatus())
                .entryCount(zipImport.getEntryCount())
                .selectableCount(zipImport.getSelectableCount())
                .doneCount(count(entries, ZipEntryStatus.DONE))
                .failedCount(count(entries, ZipEntryStatus.FAILED))
                .remainingCount(count(entries, ZipEntryStatus.QUEUED) + count(entries, ZipEntryStatus.IMPORTING))
                .archiveAvailable(zipImport.getStoragePath() != null)
                .expiresAt(zipImport.getExpiresAt())
                .errorCode(zipImport.getErrorCode())
                .message(zipImport.getErrorMessage())
                .createdAt(zipImport.getCreatedAt())
                .entries(entries.stream().map(ZipImportEntryResponse::of).toList())
                .build();
    }

    private static int count(List<ZipImportEntry> entries, ZipEntryStatus status) {
        return (int) entries.stream().filter(e -> e.getStatus() == status).count();
    }
}
