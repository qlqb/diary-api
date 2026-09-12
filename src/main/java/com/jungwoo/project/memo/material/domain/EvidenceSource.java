package com.jungwoo.project.memo.material.domain;

/**
 * 모델이 무엇을 보고 판단했는지에 대한 자기 신고. 서버가 덮어쓰지 않는다.
 *
 * "서버가 근거를 확인했는가"는 별개의 축이다(ProposalMemberResponse.evidenceVerified).
 * 둘을 한 enum에 섞으면 모델이 인용문을 지어냈을 때 사용자에게 "파일명만 보고 고른
 * 이름이에요"라고 잘못 설명하게 된다.
 */
public enum EvidenceSource {
    /** 본문 발췌에서 확인했다고 신고했다. */
    CONTENT,
    /** 파일명만 보고 판단했다고 신고했다. */
    FILENAME_ONLY
}
