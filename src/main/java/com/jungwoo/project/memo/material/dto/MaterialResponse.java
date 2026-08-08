package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MaterialResponse {

    private Long materialId;
    private Long courseId;
    private MaterialType materialType;
    private String originalFilename;
    private String contentType;
    private Long sizeBytes;
    private ExtractionStatus extractionStatus;
    private String extractionError;
    private LocalDateTime createdAt;

    public static MaterialResponse of(CourseMaterial material) {
        return MaterialResponse.builder()
                .materialId(material.getMaterialId())
                .courseId(material.getCourseId())
                .materialType(material.getMaterialType())
                .originalFilename(material.getOriginalFilename())
                .contentType(material.getContentType())
                .sizeBytes(material.getSizeBytes())
                .extractionStatus(material.getExtractionStatus())
                .extractionError(material.getExtractionError())
                .createdAt(material.getCreatedAt())
                .build();
    }
}
