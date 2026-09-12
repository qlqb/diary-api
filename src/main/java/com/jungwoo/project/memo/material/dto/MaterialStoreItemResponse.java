package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 전역 자료함의 자료 하나.
 *
 * courseId/materialType 단일 값을 담지 않는다 — 자료는 여러 프로젝트에 걸릴 수 있고,
 * 그때 성격도 각각 다르다. 대신 links에 연결을 전부 싣는다.
 * links가 비어 있으면 아직 어떤 프로젝트에도 연결되지 않은 자료다. 정상 상태다.
 */
@Getter
@Builder
public class MaterialStoreItemResponse {

    private Long materialId;
    private String originalFilename;
    private String contentType;
    private Long sizeBytes;
    private ExtractionStatus extractionStatus;
    private String extractionError;
    private LocalDateTime createdAt;
    private List<MaterialLinkResponse> links;

    public static MaterialStoreItemResponse of(CourseMaterial material, List<MaterialLinkResponse> links) {
        return MaterialStoreItemResponse.builder()
                .materialId(material.getMaterialId())
                .originalFilename(material.getOriginalFilename())
                .contentType(material.getContentType())
                .sizeBytes(material.getSizeBytes())
                .extractionStatus(material.getExtractionStatus())
                .extractionError(material.getExtractionError())
                .createdAt(material.getCreatedAt())
                .links(links)
                .build();
    }
}
