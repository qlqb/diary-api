package com.jungwoo.project.memo.material.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 제안 생성 요청.
 *
 * materialIds가 비어 있으면 그 사용자의 미연결 자료 전체를 대상으로 한다. 값을 주는 경로는
 * 둘이다 — 방금 올린 배치, 그리고 직전 응답의 remainingMaterialIds("남은 N개 보기").
 */
@Getter
@Setter
public class LinkProposalRequest {

    private List<Long> materialIds;
}
