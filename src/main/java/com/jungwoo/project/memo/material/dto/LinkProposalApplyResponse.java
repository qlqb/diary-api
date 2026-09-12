package com.jungwoo.project.memo.material.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * apply 결과. 단일 트랜잭션이라 부분 성공이 없으므로 실패 목록이 없다 — 200이면 요청 전부가
 * 적용된 것이고, 예외면 아무것도 적용되지 않은 것이다.
 */
@Getter
@Builder
public class LinkProposalApplyResponse {

    /** 이번에 새로 만든 프로젝트. 프론트가 프로젝트 목록을 갱신하는 데 쓴다. */
    private List<ProjectRef> createdProjects;

    /** 실제로 연결한 자료 수. */
    private int linkedMaterialCount;
}
