package com.jungwoo.project.memo.material.linkproposal;

/**
 * 제안 생성 결과의 상태. groups가 비었을 때 이유를 구분하기 위해 필요하다.
 *
 * groups=[] 하나로는 "정리할 게 없음"과 "AI가 대답을 못 함"을 구분할 수 없고, 프론트가
 * 재시도를 줄지 말지 판단하려면 이 셋이 필요하다.
 *
 * 자료 도메인 자체의 상태가 아니라 이 기능의 실행 결과라서 domain이 아니라 여기 둔다.
 */
public enum ProposalStatus {
    /** 모델이 답했고 보정을 마쳤다. groups가 비어 있을 수도 있다. */
    GENERATED,
    /** 정리할 미연결 자료가 없다. 모델을 부르지 않았다. */
    NO_CANDIDATES,
    /** 모델 호출 또는 파싱이 실패했다. */
    UNAVAILABLE
}
