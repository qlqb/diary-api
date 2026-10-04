package com.jungwoo.project.memo.plan;

import java.util.Locale;

/**
 * 이번 계획을 왜 만드는가. 목적마다 계획 범위의 근거가 다르다(2026-10-04).
 *
 * <ul>
 *   <li>REVIEW(복습·수업 따라잡기): 확인된 수업 진행과 사용자가 복습하려는 범위</li>
 *   <li>PREVIEW(예습): 확인된 예정 범위 또는 사용자가 고른 다음 학습 범위</li>
 *   <li>EXAM(시험 준비): 확인된 시험 범위·형식·남은 시간·사용자 어려움. 목차 전체를 시험 범위로 보지 않는다</li>
 *   <li>SELF_STUDY(독학·전체 훑기): 사용자가 고른 교재 범위와 학습 목표. 실제 수업 진도를 요구하지 않는다</li>
 *   <li>UNSPECIFIED: 말하지 않았다(예전 동작)</li>
 * </ul>
 * 값은 계획 화면에서 고른 것(요청) 또는 상담 합의(PURPOSE)에서만 온다 — 지시 문장을 몰래 분류해 정하지 않는다.
 */
public enum PlanPurpose {
    REVIEW("복습·수업 따라잡기"),
    PREVIEW("예습"),
    EXAM("시험 준비"),
    SELF_STUDY("독학·전체 훑기"),
    UNSPECIFIED("말하지 않음");

    private final String label;

    PlanPurpose(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 요청 값(enum 이름). 모르면 null. */
    public static PlanPurpose parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if ("CATCH_UP".equals(v)) {
            return REVIEW;
        }
        for (PlanPurpose p : values()) {
            if (p.name().equals(v)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 상담 합의(PURPOSE 항목)의 문장을 목적으로. 앞의 영문 이름표("EXAM: …")를 먼저 보고, 없으면 낱말로 본다.
     * 여러 목적이 섞이면 시험 > 복습 > 예습 > 독학 순으로 하나를 고른다(시험이 있으면 범위 근거가 가장 좁다).
     */
    public static PlanPurpose fromBriefText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        int colon = t.indexOf(':');
        if (colon > 0) {
            PlanPurpose tagged = parse(t.substring(0, colon));
            if (tagged != null) {
                return tagged;
            }
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(시험|중간고사|기말고사|퀴즈|exam|midterm|final).*")) {
            return EXAM;
        }
        if (lower.matches(".*(복습|따라잡|밀린|지난 수업|review).*")) {
            return REVIEW;
        }
        if (lower.matches(".*(예습|미리|다음 수업|preview).*")) {
            return PREVIEW;
        }
        if (lower.matches(".*(독학|혼자|훑|전체를|처음부터 끝까지|self).*")) {
            return SELF_STUDY;
        }
        return null;
    }

    /**
     * "← 첫 미학습"(기록 기준 첫 항목)을 출발점 표시로 쓸 수 있는가. 복습·시험은 수업 진행·시험 범위가 근거라 기록이 없다는
     * 사실만으로 1단원부터 시작하지 않는다.
     */
    public boolean allowsFirstUnlearnedAnchor() {
        return this == PREVIEW || this == SELF_STUDY || this == UNSPECIFIED;
    }

    /** 계획 프롬프트의 목적 블록. */
    public String promptRule() {
        return switch (this) {
            case REVIEW -> "[계획 목적: 복습·수업 따라잡기] 범위는 확인된 실제 수업 진행과 사용자가 복습하려는 범위다. 교재 목차 전체나 "
                    + "기록 없는 첫 항목을 범위로 삼지 않는다. 수업 진행을 모르면 가정(예: 확인된 마지막 수업까지)을 밝히고 초안을 만들며, "
                    + "계획이 크게 달라지는 경우에만 missingInformation에 진도 질문 하나를 남긴다.";
            case PREVIEW -> "[계획 목적: 예습] 범위는 확인된 예정 수업 범위(강의계획서·공지) 또는 사용자가 고른 다음 범위다. 예정 범위를 "
                    + "모르면 기록 기준 첫 항목에서 시작할 수 있다. 실제 수업 진도를 필수로 요구하지 않는다.";
            case EXAM -> "[계획 목적: 시험 준비] 범위는 확인된 시험 범위·형식·남은 시간·사용자의 어려움이다. 교재 목차 전체를 시험 범위로 "
                    + "보지 않는다. 시험 범위가 확인되지 않았으면 가정을 밝히고(예: 확인된 수업 범위) 초안을 만들며 missingInformation에 "
                    + "시험 범위 질문 하나를 남긴다. 시험 범위도 수업 범위도 확인되지 않았으면 목차 전체로 채우지 말고, 범위와 상관없이 "
                    + "쓸모 있는 작은 일(시험 범위·형식 확인, 교재 전체 구조(목차) 훑어 단원 사이 연결 파악 등)로 이번 기간 초안을 만든다 — 빈 계획을 내지 않는다. "
                    + "시험 비중이나 과목 성격만으로 학습 방법·시간 배분을 고정하지 않는다.";
            case SELF_STUDY -> "[계획 목적: 독학·전체 훑기] 범위는 사용자가 고른 교재 범위와 학습 목표다. 실제 수업 진도를 요구하지 않는다. "
                    + "목차 순서대로 나눌 수 있지만 사용자가 이미 아는 항목(표식·기록)은 빼고, 기록이 없다는 것을 모른다는 뜻으로 보지 않는다.";
            case UNSPECIFIED -> "[계획 목적: 말하지 않음] 사용자의 지시·합의에서 목적이 드러나면 그 목적의 범위 원칙을 따르고, 드러나지 않으면 "
                    + "가정을 밝힌다. 교재 목차 전체를 밀린 일이나 시험 범위로 보지 않는다.";
        };
    }
}
