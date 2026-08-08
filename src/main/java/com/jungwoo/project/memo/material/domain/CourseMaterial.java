package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 업로드된 학습 자료의 메타데이터. course_materials 테이블과 1:1 대응.
 *
 * 파일 원문은 디스크에 저장되고, 이 엔티티는 경로/추출 텍스트/추출 상태만 갖는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseMaterial {

    private Long materialId;

    private Long userId;

    private Long courseId;

    private MaterialType materialType;

    private String originalFilename;

    /** UUID 기반 안전한 파일명. 디스크 상의 실제 파일명이자 storage 조회 키. */
    private String storedFilename;

    private String contentType;

    private Long sizeBytes;

    private ExtractionStatus extractionStatus;

    private String extractedText;

    private String extractionError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
