package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.EvidenceSource;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;

import java.util.List;

/**
 * 모델이 뱉은 것을 그대로 담는 그릇. 저장하지 않는다 — 보정을 거쳐 응답으로만 나간다.
 *
 * 여기에 status를 넣지 않는다. 상태는 서비스가 정하고, 호출 실패는
 * MaterialLinkProposalService.callModel()의 Optional.empty()가 표현한다.
 */
public record LinkProposalPayload(List<ProposalGroup> groups) {

    public record ProposalGroup(
            ProposalAction action,
            /** LINK_EXISTING일 때만 채워진다. */
            Long existingCourseId,
            /** CREATE_AND_LINK일 때만 채워진다. */
            String proposedTitle,
            String reason,
            List<ProposalMember> members
    ) {
    }

    public record ProposalMember(
            Long materialId,
            MaterialType materialType,
            String evidence,
            /** 모델의 자기 신고. 서버가 덮어쓰지 않는다. */
            EvidenceSource evidenceSource
    ) {
    }
}
