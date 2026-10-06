package com.jungwoo.project.memo.material.domain;

/**
 * 미연결 자료 묶음을 어떻게 처리할지에 대한 제안. 제안일 뿐이고, 사용자가 승인하기 전까지
 * 아무것도 바뀌지 않는다.
 *
 * LEAVE는 실패가 아니라 정상적인 결과다 — 근거 없이 묶는 것보다 그냥 두는 쪽이 안전하다.
 */
public enum ProposalAction {
    LINK_EXISTING,
    CREATE_AND_LINK,
    LEAVE
}
