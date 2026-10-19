package com.jungwoo.project.memo.learning.week;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 글에서 "주차"를 읽는다. 읽기만 한다 — 읽은 값이 실제 수업 주차인지는 여기서 정하지 않는다.
 *
 * <p>범위는 범위다. "1~3주차"는 [1, 2, 3]이다 — 끝 숫자 하나(3)로도, 시작 숫자 하나(1)로도 줄이지 않는다.
 * 예전 정규식이 끝 숫자만 잡아 강의계획서의 "1~3주차 오리엔테이션" 구간이 3주차 칸을 차지했다.
 *
 * <p>세 가지 읽기가 있고 강도가 다르다.
 * <ul>
 *   <li>{@link #weeksIn}: 본문·제목의 "N주차" 표기(범위 포함). 강의계획서의 예정 진도도 이것으로 읽는다.</li>
 *   <li>{@link #filenameWeeks}: 파일명의 명시적 "N주차"·"N주". 다운로드 중복 접미사 "(1)"은 주차가 아니다.</li>
 *   <li>{@link #leadingNumber}: 파일명 앞의 순번("3.AWS…"). 약한 단서일 뿐 주차 표기가 아니다.</li>
 * </ul>
 */
public final class WeekExpressions {

    /** 주차로 인정하는 범위. 학기 한 번이 이 안에 든다. */
    public static final int MAX_WEEK = 30;

    private static final String DASH = "[~\\-–—∼〜～]";

    /** "1~3주차", "1주차~3주차", "제1~3주차", "1 ~ 3 주차". */
    private static final Pattern RANGE = Pattern.compile(
            "(?<!\\d)(?:제\\s*)?(\\d{1,2})\\s*(?:주\\s*차)?\\s*" + DASH + "\\s*(?:제\\s*)?(\\d{1,2})\\s*주\\s*차");

    /** "3주차", "제3주차", "3 주 차". */
    private static final Pattern SINGLE = Pattern.compile("(?<!\\d)(?:제\\s*)?(\\d{1,2})\\s*주\\s*차");

    /**
     * 파일명의 범위: "1~3주차", "1-3주". 뒤에 한글이 이어지면 주차가 아니다("12주년").
     */
    private static final Pattern FILE_RANGE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*(?:주\\s*차?)?\\s*" + DASH + "\\s*(\\d{1,2})\\s*주(?:\\s*차)?(?![가-힣])");

    /** 파일명의 "3주차", "3주_2", "3주__1". "3주년"·"3주기"처럼 한글이 이어지면 아니다. */
    private static final Pattern FILE_SINGLE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*주(?:\\s*차)?(?![가-힣])");

    /** 다운로드·복사가 붙이는 꼬리: "(1)", " (4)", "- 복사본", "copy". 여러 번 붙기도 한다. */
    private static final Pattern DOWNLOAD_SUFFIX = Pattern.compile(
            "(?:\\s*\\(\\d{1,3}\\)|\\s*[-_]?\\s*복사본|\\s*[-_]?\\s*(?i:copy))\\s*$");

    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,6}$");

    /**
     * 파일명 앞의 순번. "01.", "3.", "2_", "1.1", "3 ". 뒤가 숫자면(연도 "2026-") 순번이 아니고,
     * 장·과·강·차시처럼 다른 단위가 붙으면 주차 단서로 쓰지 않는다("4장_1 문제", "0과 Orientation").
     */
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^\\s*(\\d{1,2})(?!\\d)(?!\\s*(?:장|과|강|차시|부|편|주|학년|학기|월|일|번))(?=[._\\-\\s]|\\p{L})");

    private WeekExpressions() {
    }

    /**
     * 글에 적힌 "N주차"를 모두. 범위는 펼친다. 오름차순·중복 없음. 없으면 빈 목록.
     *
     * <p>뒤집힌 범위("5~3주차")는 버린다 — 오타인지 무엇인지 알 수 없는 값으로 칸을 채우지 않는다.
     */
    public static List<Integer> weeksIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String value = normalize(text);
        TreeSet<Integer> weeks = new TreeSet<>();
        StringBuilder rest = new StringBuilder(value);
        Matcher range = RANGE.matcher(value);
        while (range.find()) {
            addRange(weeks, range.group(1), range.group(2));
            blank(rest, range.start(), range.end());
        }
        Matcher single = SINGLE.matcher(rest);
        while (single.find()) {
            addOne(weeks, single.group(1));
        }
        return List.copyOf(weeks);
    }

    /**
     * 파일명이 스스로 밝힌 주차. "1주차.pdf" → [1], "3주_2.pdf" → [3], "2주차 (1).pdf" → [2],
     * "1~3주차 총정리.pdf" → [1, 2, 3], "네트워크프로그래밍(4).pdf" → [].
     */
    public static List<Integer> filenameWeeks(String filename) {
        String stem = stem(filename);
        if (stem.isEmpty()) {
            return List.of();
        }
        TreeSet<Integer> weeks = new TreeSet<>();
        StringBuilder rest = new StringBuilder(stem);
        Matcher range = FILE_RANGE.matcher(stem);
        while (range.find()) {
            addRange(weeks, range.group(1), range.group(2));
            blank(rest, range.start(), range.end());
        }
        Matcher single = FILE_SINGLE.matcher(rest);
        while (single.find()) {
            addOne(weeks, single.group(1));
        }
        return List.copyOf(weeks);
    }

    /**
     * 파일명 앞의 순번(1~{@value #MAX_WEEK}). 없으면 null. "3.AWS_구성하기.pdf" → 3, "01.수업소개.pdf" → 1,
     * "ch02_배열.pptx" → null, "0과 Orientation.pdf" → null.
     *
     * <p>이 숫자는 주차가 아니라 <b>순서</b>다. 호출부는 이것만으로 주차를 확정하지 않는다.
     */
    public static Integer leadingNumber(String filename) {
        String stem = stem(filename);
        Matcher m = LEADING_NUMBER.matcher(stem);
        if (!m.find()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= MAX_WEEK ? n : null;
    }

    /**
     * 확장자와 다운로드 꼬리를 뗀 파일명. 유니코드는 NFC로 맞춘다(macOS에서 온 이름은 자모가 풀려 있다).
     */
    public static String stem(String filename) {
        if (filename == null) {
            return "";
        }
        String value = normalize(filename).trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = EXTENSION.matcher(value).replaceFirst("");
        String previous;
        do {
            previous = value;
            value = DOWNLOAD_SUFFIX.matcher(value).replaceFirst("").trim();
        } while (!value.equals(previous));
        return value;
    }

    /** 범위 표기를 사람이 읽는 말로: [1,2,3] → "1~3주차", [8] → "8주차", [1,2,5] → "1~2, 5주차". */
    public static String describe(List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int start = weeks.get(0);
        int prev = start;
        for (int i = 1; i <= weeks.size(); i++) {
            Integer cur = i < weeks.size() ? weeks.get(i) : null;
            if (cur != null && cur == prev + 1) {
                prev = cur;
                continue;
            }
            parts.add(start == prev ? String.valueOf(start) : start + "~" + prev);
            if (cur != null) {
                start = cur;
                prev = cur;
            }
        }
        return String.join(", ", parts) + "주차";
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private static void addRange(TreeSet<Integer> weeks, String from, String to) {
        int a = Integer.parseInt(from);
        int b = Integer.parseInt(to);
        if (a < 1 || b > MAX_WEEK || a > b) {
            return;
        }
        for (int w = a; w <= b; w++) {
            weeks.add(w);
        }
    }

    private static void addOne(TreeSet<Integer> weeks, String value) {
        int w = Integer.parseInt(value);
        if (w >= 1 && w <= MAX_WEEK) {
            weeks.add(w);
        }
    }

    /** 이미 범위로 읽은 자리를 공백으로 지워 단일 표기로 한 번 더 읽히지 않게 한다. */
    private static void blank(StringBuilder text, int start, int end) {
        for (int i = start; i < end; i++) {
            text.setCharAt(i, ' ');
        }
    }
}
