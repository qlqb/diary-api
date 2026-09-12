package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 업로드된 학습 자료 원본의 메타데이터. course_materials 테이블과 1:1 대응.
 *
 * 맥락(어느 프로젝트의 무슨 자료인가)을 갖지 않는다 — 그건 MaterialLink의 책임이다.
 * 이 엔티티는 파일 원문 자체(경로/추출 텍스트/추출 상태)와 사용자 소유권만 갖는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseMaterial {

    private Long materialId;

    private Long userId;

    private String originalFilename;

    /** UUID 기반 안전한 파일명. 화면 표시·로그에 쓴다. */
    private String storedFilename;

    /** uploadDir 기준 상대 경로. FileStorageService.resolve()가 실제 디스크 경로를 찾을 때 쓴다. */
    private String storagePath;

    private String contentType;

    private Long sizeBytes;

    /** SHA-256. 신규 업로드부터만 채워진다 — 기존 자료는 NULL일 수 있다. 판단에 쓰지 않는다. */
    private String fileHash;

    private ExtractionStatus extractionStatus;

    private String extractedText;

    private String extractionError;

    private MaterialStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
