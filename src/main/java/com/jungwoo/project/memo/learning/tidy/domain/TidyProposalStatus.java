package com.jungwoo.project.memo.learning.tidy.domain;

/**
 * project_tidy_proposals.status.
 *
 * <p>SUPERSEDED는 [새 자료 반영해 다시 정리]로 새 판이 생겨 물러난 것이다 — 버린 것이 아니라
 * 이력이다. DISMISSED(버리기)와 구분해야 한다: 버린 것은 다시 살아나지 않고, 물러난 것은
 * 새 판이 그 자리를 이어받는다.
 *
 * <p>EMPTY는 "바꿀 것이 없었다"이다. 실패가 아니다.
 */
public enum TidyProposalStatus {
    PROPOSED,
    APPLIED,
    DISMISSED,
    SUPERSEDED,
    EMPTY;

    public boolean isOpen() {
        return this == PROPOSED;
    }
}
