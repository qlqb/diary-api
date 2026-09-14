package com.jungwoo.project.memo.commitment.domain;

/**
 * 이 약속이 원본 근무의 앞인가 뒤인가.
 *
 * <p>{@link CommitmentSourceType}과 다른 것을 담는다. 출처는 "누가 만들었나"이고 이것은
 * "무엇에서 파생됐나"다. 근무표 이미지로 등록한 진짜 근무도 AI 출처라, 출처만으로는
 * 원본 근무와 그 근무에서 만들어진 이동 블록을 구분할 수 없다.
 *
 * <p>제목으로 구분하지 않는 이유는 제목이 사용자가 언제든 바꾸는 값이기 때문이다.
 * "근무 후 이동"을 "퇴근길"로 고치면 제목 규칙은 그 행을 다시 근무로 본다.
 *
 * <p>클라이언트가 보내는 값이 아니다. draft 후보를 만들 때 서버가 정하고, 적용 시점에도
 * 사용자가 고친 payload가 아니라 저장된 원본 payload에서 읽는다.
 */
public enum DerivedTravelRelation {

    /** 원본 근무 시작 직전. 종료 = 근무 시작. */
    BEFORE_WORK,

    /** 원본 근무 종료 직후. 시작 = 근무 종료. */
    AFTER_WORK;

    public static DerivedTravelRelation from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
