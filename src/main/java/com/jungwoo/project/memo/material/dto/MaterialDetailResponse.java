package com.jungwoo.project.memo.material.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 자료 상세: 자료 + 연결된 프로젝트 + 이 자료로 만든 분석 이력.
 *
 * 분석 이력은 프로젝트별로 남는다(같은 자료를 A와 B에서 각각 해석할 수 있다).
 * 연결을 끊어도 분석 레코드는 지우지 않는다 — 조회는 되고 적용만 막힌다.
 */
@Getter
@Builder
public class MaterialDetailResponse {

    private MaterialStoreItemResponse material;
    private List<MaterialAnalysisResponse> analyses;
}
