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
    /** 읽었지만 일부를 못 읽은 경우의 안내. 성공이어도 채워질 수 있다. */
    private String extractionWarning;
    private LocalDateTime createdAt;
    private List<MaterialLinkResponse> links;

    /** CONSULT_PHOTO(상담 사진)면 값이 있다. 예전 업로드는 null. */
    private String origin;
    /** 지금 원본을 볼 수 있나(상담 사진은 30일 뒤·사용자가 지우면 false). */
    private boolean originalAvailable;
    private LocalDateTime originalExpiresAt;

    public static MaterialStoreItemResponse of(CourseMaterial material, List<MaterialLinkResponse> links) {
        return MaterialStoreItemResponse.builder()
                .materialId(material.getMaterialId())
                .originalFilename(material.getOriginalFilename())
                .contentType(material.getContentType())
                .sizeBytes(material.getSizeBytes())
                .extractionStatus(material.getExtractionStatus())
                .extractionError(material.getExtractionError())
                .extractionWarning(material.getExtractionWarning())
                .createdAt(material.getCreatedAt())
                .links(links)
                .origin(material.getOrigin() == null ? null : material.getOrigin().name())
                .originalAvailable(material.originalAvailable(LocalDateTime.now()))
                .originalExpiresAt(material.getOriginalExpiresAt())
                .build();
    }
}
