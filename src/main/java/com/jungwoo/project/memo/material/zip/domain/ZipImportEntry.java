package com.jungwoo.project.memo.material.zip.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 압축 안 파일 하나(material_zip_import_entries).
 *
 * <p>entryPath는 압축 안의 원래 경로다 — 사용자에게 보이고 자료의 출처로 남지만, 파일시스템
 * 경로로는 절대 쓰지 않는다(저장 이름은 UUID다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZipImportEntry {

    private Long entryId;
    private Long importId;
    private Long userId;
    /** 압축 안의 순번. (importId, entryIndex)가 유일하다. */
    private Integer entryIndex;
    private String entryPath;
    /** 목록·자료 이름에 쓰는 파일명(경로의 마지막 조각). */
    private String displayName;
    private String extension;
    private Long sizeBytes;
    private Boolean supported;
    /** 고를 수 없는 이유(지원하지 않는 형식, 중복 경로, 크기 초과 등). */
    private String skipReason;
    private ZipEntryStatus status;
    /** 자료가 됐으면 그 id. 한 항목이 자료를 두 개 만들지 않도록 UNIQUE다. */
    private Long materialId;
    private String errorCode;
    private String errorMessage;
    private Integer attempt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
