package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.linkproposal.ProposalStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 자료 연결 제안 응답.
 *
 * 정적 팩터리 셋을 두는 이유: 상태별로 어떤 필드가 어떤 값이어야 하는지를 한 곳에 가둔다.
 * 호출부에서 status와 groups를 따로 세팅하면 UNAVAILABLE인데 groups가 차 있는 조합이
 * 만들어질 수 있다.
 */
@Getter
@Builder
public class LinkProposalResponse {

    private ProposalStatus status;
    private List<ProposalGroupResponse> groups;

    /**
     * 이번에 다루지 않은 미연결 자료. 개수가 아니라 id 목록인 것이 핵심이다 — 프론트가 이
     * 배열을 그대로 다음 요청에 넘기면 커서처럼 동작해서, 앞의 12개가 전부 LEAVE로 남아도
     * 13번째 자료가 영원히 제안에 오르지 않는 일이 없다.
     */
    private List<Long> remainingMaterialIds;

    public static LinkProposalResponse generated(List<ProposalGroupResponse> groups, List<Long> remaining) {
        return LinkProposalResponse.builder()
                .status(ProposalStatus.GENERATED)
                .groups(groups == null ? List.of() : groups)
                .remainingMaterialIds(remaining == null ? List.of() : remaining)
                .build();
    }

    /** 정리할 미연결 자료가 없다. 모델을 부르지 않았다. */
    public static LinkProposalResponse noCandidates() {
        return LinkProposalResponse.builder()
                .status(ProposalStatus.NO_CANDIDATES)
                .groups(List.of())
                .remainingMaterialIds(List.of())
                .build();
    }

    /** 모델 호출 또는 파싱이 실패했다. 제안은 부가 기능이라 200으로 내려간다. */
    public static LinkProposalResponse unavailable() {
        return LinkProposalResponse.builder()
                .status(ProposalStatus.UNAVAILABLE)
                .groups(List.of())
                .remainingMaterialIds(List.of())
                .build();
    }
}
