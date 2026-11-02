package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Material Agent 분석 결과 초안. course_material_analyses 테이블과 1:1 대응.
 *
 * DRAFT 상태는 courses/course_topics에 전혀 영향을 주지 않는다. apply()가 호출된
 * 순간에만 course_topics가 생성되고 courses의 교재 필드가 채워진다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseMaterialAnalysis {

    private Long analysisId;

    private Long userId;

    private Long courseId;

    private Long materialId;

    private MaterialAnalysisStatus status;

    /** AI가 생성한 원본 구조화 결과(JSON 문자열). */
    private String analysisJson;

    /** 사용자가 검토 중 수정한 내용(JSON 문자열). apply 시 있으면 이걸, 없으면 analysisJson을 확정한다. */
    private String editedJson;

    private String model;

    private String failureReason;

    private LocalDateTime createdAt;

    private LocalDateTime appliedAt;
}
