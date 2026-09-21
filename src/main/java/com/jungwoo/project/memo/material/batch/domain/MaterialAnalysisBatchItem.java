package com.jungwoo.project.memo.material.batch.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 묶음의 자리 하나. material_analysis_batch_items.
 *
 * <p>묶음을 만들 때는 materialId가 없다(파일을 고르기만 한 상태). 업로드가 그 자리에 채운다.
 * 실패해도 자리는 남는다 — 5개 골랐는데 3개만 보이면 무엇이 빠졌는지 알 수 없다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialAnalysisBatchItem {

    private Long itemId;
    private Long batchId;
    private Long userId;
    private Integer position;
    private String filename;
    private Long sizeBytes;
    private String extension;
    private Long materialId;
    private BatchItemUploadState uploadState;
    private Integer estMinSeconds;
    private Integer estMaxSeconds;
    private String estBasis;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
