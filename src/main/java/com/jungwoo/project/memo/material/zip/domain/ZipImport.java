package com.jungwoo.project.memo.material.zip.domain;

import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 압축 파일 가져오기 한 건(material_zip_imports).
 *
 * <p>원본 zip은 자료가 아니다 — 임시 보관했다가 가져오기가 끝나거나 기한이 지나면 지운다.
 * 그때도 이미 만들어진 개별 자료는 각자 파일을 갖고 있어 영향받지 않는다.
 *
 * <p>courseId가 있으면 프로젝트 화면에서 시작한 가져오기라, 만들어지는 자료가 그 프로젝트에
 * 연결된다. 없으면 자료함에서 시작한 것이라 연결 없이 등록된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZipImport {

    private Long importId;
    private Long userId;
    private Long courseId;
    private MaterialType materialType;
    private String originalFilename;
    /** uploadDir 기준 상대 경로(zip-imports/...). 정리 대상이다. */
    private String storagePath;
    private Long sizeBytes;
    private String fileHash;
    private ZipImportStatus status;
    private Integer entryCount;
    private Integer selectableCount;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
