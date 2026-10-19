package com.jungwoo.project.memo.course.textbook;

import java.util.regex.Pattern;

/**
 * 목차 항목 제목이 문제·실습·요약 묶음인가. 목차 제목만 확보한 항목은 "범위"다 — 그 안의 문제 내용은 모른다.
 */
public enum TocItemKind {

    /** 연습문제·확인 문제·Exercises·Quiz 등 */
    EXERCISE,
    /** 실습·Lab */
    LAB,
    /** 요약·정리·Summary */
    SUMMARY;

    private static final Pattern EXERCISE_WORDS = Pattern.compile(
            "(연습\\s*문제|확인\\s*문제|도전\\s*문제|종합\\s*문제|실전\\s*문제|Exercises?|EXERCISES?|Quiz|QUIZ|Problems|Review\\s+Questions)");
    private static final Pattern LAB_WORDS = Pattern.compile("^\\s*(실습|Lab\\b|LAB\\b)");
    private static final Pattern SUMMARY_WORDS = Pattern.compile("^\\s*(요약|정리|핵심\\s*정리|단원\\s*정리|Summary|SUMMARY)\\s*$");

    /** 제목으로 종류를 본다. 문제 묶음이 섞인 "요약/연습문제"는 EXERCISE다. 아니면 null. */
    public static TocItemKind of(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        if (EXERCISE_WORDS.matcher(title).find()) {
            return EXERCISE;
        }
        if (LAB_WORDS.matcher(title).find()) {
            return LAB;
        }
        if (SUMMARY_WORDS.matcher(title).find()) {
            return SUMMARY;
        }
        return null;
    }
}
