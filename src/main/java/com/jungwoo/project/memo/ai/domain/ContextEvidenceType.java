package com.jungwoo.project.memo.ai.domain;

/**
 * 사용자 상황 한 줄이 무엇에 근거하는가. 넷을 섞지 않는다 — 자료를 갖고 있다는 것, 해 봤다는 것, 안다고 말한 것,
 * 혼자 해낸 것은 서로 다른 사실이다.
 */
public enum ContextEvidenceType {
    /** 사용자가 직접 말했거나 직접 고쳤다. */
    STATED,
    /** 사용자의 자기평가("알아·애매해·처음 봐", "따라는 했는데 혼자는 못 해"). 숙달의 증거가 아니다. */
    SELF_REPORT,
    /** 서버가 실행 기록에서 확인한 관찰. */
    OBSERVED,
    /** AI의 추정. 사용자가 확인하기 전에는 사실로 쓰지 않는다. */
    INFERRED;

    public String label() {
        return switch (this) {
            case STATED -> "사용자가 말함";
            case SELF_REPORT -> "자기평가";
            case OBSERVED -> "실행 기록에서 확인";
            case INFERRED -> "AI 추정, 확인 전";
        };
    }
}
