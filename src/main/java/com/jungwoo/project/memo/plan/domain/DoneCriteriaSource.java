package com.jungwoo.project.memo.plan.domain;

/**
 * 완료 기준을 누가 썼는가.
 *
 * <p>화면이 이 값으로 작은 `기본` 라벨을 붙인다. 서버가 만든 문장은 항목 제목에서 기계적으로
 * 뽑은 것이라 모델이 쓴 문장보다 덜 구체적이고, 그 사실을 숨기면 사용자는 왜 어떤 조각의
 * 완료 기준만 밋밋한지 알 수 없다.
 *
 * <p>이 값이 DEFAULT로 자주 찍히면 프롬프트를 손봐야 한다는 신호이기도 하다.
 */
public enum DoneCriteriaSource {

    /** 모델이 쓴 문장. */
    MODEL,

    /** 모델이 못 썼거나 "공부하기" 류여서 서버가 채운 문장. */
    DEFAULT
}
