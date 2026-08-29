package com.jungwoo.project.memo.material.linkproposal;

/**
 * 제안 요청이 어느 경로에서 왔는지.
 *
 * 30일 자기 검증 동안 "자동 제안이 실제로 카드로 이어졌는지"를 서버 로그만으로 세기 위해
 * 있다. 프론트의 console.info는 개발자 도구를 열어둬야만 남아서 며칠 단위 관찰에 쓸 수 없다.
 *
 * 서버가 "카드가 떴는지"를 따로 판정하지 않는다 — trigger=AUTO이고 selectable=0이면 안 뜬
 * 것으로 읽으면 된다. 프론트의 게이트 규칙을 서버에 복제하면 두 곳이 갈라진다.
 *
 * ProposalStatus와 같은 이유로 domain이 아니라 여기 둔다. 자료 도메인의 상태가 아니라
 * 이 기능의 실행 맥락이다.
 */
public enum ProposalTrigger {
    /** 배치 업로드 직후 자동 호출. 사용자가 요청하지 않았다. */
    AUTO,
    /** `프로젝트로 정리하기`. 사용자가 눌러서 불렀다. */
    MANUAL,
    /** `남은 N개 보기` — 직전 응답의 remainingMaterialIds로 다음 묶음을 본다. */
    REMAINING,
    /** `다시 시도`. 직전 trigger가 아니라 항상 이 값이다 — 재시도 자체를 세는 것이 목적이다. */
    RETRY;

    /** 요청 바디에 trigger가 없으면 수동으로 본다 — 기존 호출을 깨지 않기 위한 기본값이다. */
    static ProposalTrigger orDefault(ProposalTrigger trigger) {
        return trigger == null ? MANUAL : trigger;
    }
}
