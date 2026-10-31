package com.jungwoo.project.memo.material.zip.dto;

import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import lombok.Builder;
import lombok.Getter;

/** 압축 안 파일 하나. entryPath는 보여주기 위한 원래 경로이고 저장 경로가 아니다. */
@Getter
@Builder
public class ZipImportEntryResponse {

    private Long entryId;
    private String entryPath;
    private String displayName;
    private String extension;
    private Long sizeBytes;
    private boolean supported;
    /** 고를 수 없는 이유. supported=false일 때만 채워진다. */
    private String skipReason;
    private ZipEntryStatus status;
    /** 자료가 됐으면 그 id. 화면이 바로 그 자료로 갈 수 있다. */
    private Long materialId;
    private String errorMessage;

    public static ZipImportEntryResponse of(ZipImportEntry entry) {
        return ZipImportEntryResponse.builder()
                .entryId(entry.getEntryId())
                .entryPath(entry.getEntryPath())
                .displayName(entry.getDisplayName())
                .extension(entry.getExtension())
                .sizeBytes(entry.getSizeBytes())
                .supported(Boolean.TRUE.equals(entry.getSupported()))
                .skipReason(entry.getSkipReason())
                .status(entry.getStatus())
                .materialId(entry.getMaterialId())
                .errorMessage(entry.getErrorMessage())
                .build();
    }
}
