package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 분석 한 건. courseId는 이 해석이 "어느 프로젝트 맥락에서 만들어졌는가"이지 자료의 속성이 아니다.
 *
 * courseTitle을 함께 싣는 이유: 전역 자료 상세는 이 자료의 모든 맥락을 한 목록에 모아 보여주는데,
 * 프로젝트명이 없으면 "분석 3건"만 보이고 어느 맥락의 것인지 알 수 없다. DB에 중복 저장하지 않고
 * 응답을 조립할 때 courses에서 채운다.
 */
@Getter
@Builder
public class MaterialAnalysisResponse {

    private Long analysisId;
    private Long courseId;
    private String courseTitle;
    private Long materialId;
    private MaterialAnalysisStatus status;
    /** editedJson이 있으면 그것을, 없으면 analysisJson을 파싱한 현재 유효한 내용. */
    private MaterialAnalysisPayload payload;
    private String failureReason;
    private Integer createdTopicCount;
    private LocalDateTime createdAt;
    private LocalDateTime appliedAt;
}
