package com.jungwoo.project.memo.material.batch.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 한 번의 [분석 시작]이 맡는 자료 묶음. material_analysis_batches.
 *
 * <p>구성원은 만들 때 고정된다. 분석 중에 파일을 더 올리면 새 묶음이 생긴다 — 기존 묶음의
 * 분모를 늘리면 진행률이 뒤로 간다.
 *
 * <p>진행률은 이 행이 아니라 작업 표에서 센다. 여기 있는 것은 "무엇이 이 묶음인가"와
 * 시작 전에 말한 예상 시간뿐이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialAnalysisBatch {

    private Long batchId;
    private Long userId;
    /** 프로젝트 화면에서 시작했으면 그 프로젝트. 자료함에서 시작했으면 null. */
    private Long courseId;
    private BatchStatus status;
    private Integer itemCount;
    private Integer estMinSeconds;
    private Integer estMaxSeconds;
    private String estBasis;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
