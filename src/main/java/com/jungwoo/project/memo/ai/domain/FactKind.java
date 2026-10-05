package com.jungwoo.project.memo.ai.domain;

import java.util.Locale;

/**
 * 프로젝트 기억 한 줄의 종류. 예전 행은 null(종류 모름 — "그 밖"으로 보인다).
 *
 * <p>PROGRESS는 <b>수업에서</b> 어디까지 했는가(사실)이고, DIFFICULTY·RESOLVED는 <b>내가</b> 어디서 막혔고 어떻게
 * 풀었는가(이해)다. "수업에서 Unit 4까지 배움"과 "내가 Unit 4를 이해함"은 다른 사실이라 섞지 않는다.
 */
public enum FactKind {
    /** 수업 진도. 과목당 현재 값 하나 — 더 늦은 사용자 발화가 대체한다. */
    PROGRESS,
    /** 시험 범위. 과목·시험 이름(fact_label)당 현재 값 하나. */
    EXAM_SCOPE,
    /** 내가 막힌 곳. 해결(RESOLVED)이 명시적으로 가리킬 때만 닫힌다. */
    DIFFICULTY,
    /** 막혔다가 풀었다(help_level: 혼자/도움받아). */
    RESOLVED,
    GOAL,
    PREFERENCE,
    CONSTRAINT,
    OTHER;

    /** 과목(·시험 이름)당 하나만 살아 있는 현재 상태형인가. */
    public boolean singleCurrent() {
        return this == PROGRESS || this == EXAM_SCOPE;
    }

    /** 모르는 값은 OTHER, 비었으면 null. */
    public static FactKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    public String label() {
        return switch (this) {
            case PROGRESS -> "수업 진도";
            case EXAM_SCOPE -> "시험 범위";
            case DIFFICULTY -> "막힌 곳";
            case RESOLVED -> "해결";
            case GOAL -> "목표";
            case PREFERENCE -> "선호";
            case CONSTRAINT -> "제약";
            case OTHER -> "그 밖";
        };
    }
}
