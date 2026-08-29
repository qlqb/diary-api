package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.EvidenceSource;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.Builder;
import lombok.Getter;

/**
 * 제안 묶음에 들어 있는 자료 한 개.
 *
 * evidenceSource(모델의 자기 신고)와 evidenceVerified(서버가 발췌 원문과 대조한 결과)는
 * 다른 축이다. "본문 근거를 확인하지 못했다"와 "파일명만 보고 판단했다"는 사용자에게
 * 다르게 설명해야 하는 다른 상태다.
 */
@Getter
@Builder
public class ProposalMemberResponse {

    private Long materialId;
    private String originalFilename;
    private MaterialType materialType;
    private String evidence;
    private EvidenceSource evidenceSource;
    private boolean evidenceVerified;
}
