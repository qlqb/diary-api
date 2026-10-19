package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.linkproposal.ProposalTrigger;
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

    /**
     * 이 호출이 어느 경로에서 왔는지. 로그로만 쓰고 동작을 바꾸지 않는다.
     * 없으면 MANUAL로 본다 — 기존 호출을 깨지 않기 위한 기본값이다.
     */
    private ProposalTrigger trigger;
}
