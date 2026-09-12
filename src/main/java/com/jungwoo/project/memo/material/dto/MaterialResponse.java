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

    /**
     * courseId/materialType은 자료가 아니라 링크에서 온다 — 같은 파일이 프로젝트마다 다른
     * 성격일 수 있기 때문이다. 응답 형태 자체는 그대로 유지한다(기존 프론트 호환).
     * 어떤 프로젝트에도 연결되지 않은 자료는 둘 다 null이다.
     */
    public static MaterialResponse of(CourseMaterial material, Long courseId, MaterialType materialType) {
        return MaterialResponse.builder()
                .materialId(material.getMaterialId())
                .courseId(courseId)
                .materialType(materialType)
                .originalFilename(material.getOriginalFilename())
                .contentType(material.getContentType())
                .sizeBytes(material.getSizeBytes())
                .extractionStatus(material.getExtractionStatus())
                .extractionError(material.getExtractionError())
                .createdAt(material.getCreatedAt())
                .build();
    }
}
