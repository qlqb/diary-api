package com.jungwoo.project.memo.material.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 자료 구간의 역할. 닫힌 집합이다 — 모델이 낸 임의 문자열을 그대로 저장하지 않는다.
 * 비슷한 뜻의 별칭은 {@link #parse}가 정리하고, 모르는 값은 OTHER로 보수적으로 처리한다.
 */
public enum SectionRole {
    /** 개념·설명 */
    CONCEPT,
    /** 예제·실습 예시(따라 해 보는 것) */
    EXAMPLE,
    /** 풀어 볼 문제·연습 */
    EXERCISE,
    /** 제출·평가 요구사항이 적힌 부분 */
    ASSIGNMENT,
    /** 일정·주차·시험 안내 */
    SCHEDULE,
    /** 운영·행정 안내(평가 비율, 연락처 등) */
    ADMIN,
    /** 요약·정리 */
    SUMMARY,
    /** 참고 자료·링크·목차 */
    REFERENCE,
    OTHER;

    private static final Map<String, SectionRole> ALIASES = Map.ofEntries(
            Map.entry("EXPLANATION", CONCEPT), Map.entry("THEORY", CONCEPT), Map.entry("LECTURE", CONCEPT),
            Map.entry("DEFINITION", CONCEPT), Map.entry("CONCEPTS", CONCEPT),
            Map.entry("EXAMPLES", EXAMPLE), Map.entry("DEMO", EXAMPLE), Map.entry("PRACTICE_EXAMPLE", EXAMPLE),
            Map.entry("LAB", EXAMPLE), Map.entry("TUTORIAL", EXAMPLE),
            Map.entry("PROBLEM", EXERCISE), Map.entry("PROBLEMS", EXERCISE), Map.entry("EXERCISES", EXERCISE),
            Map.entry("QUIZ", EXERCISE), Map.entry("PRACTICE", EXERCISE), Map.entry("TASK", EXERCISE),
            Map.entry("HOMEWORK", ASSIGNMENT), Map.entry("SUBMISSION", ASSIGNMENT), Map.entry("REQUIREMENT", ASSIGNMENT),
            Map.entry("REQUIREMENTS", ASSIGNMENT), Map.entry("PROJECT", ASSIGNMENT), Map.entry("REPORT", ASSIGNMENT),
            Map.entry("DEADLINE", SCHEDULE), Map.entry("EXAM", SCHEDULE), Map.entry("CALENDAR", SCHEDULE),
            Map.entry("TIMETABLE", SCHEDULE), Map.entry("SYLLABUS", SCHEDULE),
            Map.entry("ADMINISTRATIVE", ADMIN), Map.entry("GRADING", ADMIN), Map.entry("POLICY", ADMIN),
            Map.entry("INFO", ADMIN), Map.entry("COURSE_INFO", ADMIN),
            Map.entry("RECAP", SUMMARY), Map.entry("REVIEW", SUMMARY), Map.entry("CONCLUSION", SUMMARY),
            Map.entry("TOC", REFERENCE), Map.entry("TABLE_OF_CONTENTS", REFERENCE), Map.entry("LINKS", REFERENCE),
            Map.entry("REFERENCES", REFERENCE), Map.entry("BIBLIOGRAPHY", REFERENCE), Map.entry("APPENDIX", REFERENCE)
    );

    /** 모델 출력의 역할 목록을 닫힌 집합으로 정리한다. 비어 있으면 OTHER 하나. */
    public static List<SectionRole> parse(Collection<String> raw) {
        Set<SectionRole> roles = new LinkedHashSet<>();
        if (raw != null) {
            for (String one : raw) {
                SectionRole role = parseOne(one);
                if (role != null) {
                    roles.add(role);
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(OTHER);
        }
        if (roles.size() > 1) {
            roles.remove(OTHER);
        }
        return new ArrayList<>(roles);
    }

    public static SectionRole parseOne(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.isEmpty()) {
            return null;
        }
        for (SectionRole role : values()) {
            if (role.name().equals(key)) {
                return role;
            }
        }
        return ALIASES.getOrDefault(key, OTHER);
    }

    /** 화면 문구. enum 원문을 화면에 내보내지 않는다. */
    public String label() {
        return switch (this) {
            case CONCEPT -> "설명";
            case EXAMPLE -> "예제";
            case EXERCISE -> "문제";
            case ASSIGNMENT -> "제출 요구";
            case SCHEDULE -> "일정";
            case ADMIN -> "운영 안내";
            case SUMMARY -> "정리";
            case REFERENCE -> "참고";
            case OTHER -> "기타";
        };
    }
}
