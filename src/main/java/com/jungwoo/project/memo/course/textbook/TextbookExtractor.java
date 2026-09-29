package com.jungwoo.project.memo.course.textbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자료 원문 텍스트에서 교재 단서를 <b>규칙으로</b> 읽는다. 모델을 부르지 않는다.
 *
 * <p>두 가지를 따로 읽는다.
 * <ul>
 *   <li><b>어느 책인가</b>(서지): ISBN(체크섬 검증), 그리고 "지은이·펴낸곳·제목·N판"처럼 원문이 이름표를 붙여 적은 값만.
 *       판권면·표지의 줄을 그대로 근거(quote)로 남긴다. 이름표 없는 큰 글씨 제목은 추측하지 않는다.</li>
 *   <li><b>그 책이 다루는 범위</b>(목차): "목차·차례·Contents" 머리가 있는 쪽과 그에 이어지는 쪽에서, 번호와 제목(과 쪽수)이
 *       있는 줄만 항목으로 읽는다. 항목이 셋 미만이면 목차를 찾지 못한 것이다 — 책 이름만으로 목차를 만들지 않는다.</li>
 * </ul>
 *
 * <p>규칙이 바뀌면 {@link #VERSION}을 올린다. 추출 결과는 (자료, 파일 해시, 판)마다 한 번 저장된다.
 */
public final class TextbookExtractor {

    public static final int VERSION = 1;

    /** 서지 단서를 찾을 앞쪽·뒤쪽 단위 수. 판권면은 보통 앞 몇 쪽이나 맨 뒤에 있다. */
    private static final int FRONT_UNITS = 8;
    private static final int BACK_UNITS = 3;
    /** 목차 머리 뒤로 이어 읽을 최대 단위 수. */
    private static final int TOC_MAX_UNITS = 8;
    private static final int MIN_TOC_ENTRIES = 3;
    private static final int MAX_TOC_ENTRIES = 400;

    public record Unit(int unitNo, String text) {
    }

    public record Field(String value, int unit, String quote) {
    }

    public record TocEntry(int level, String number, String title, Integer page, int unit) {
    }

    public record Result(Map<String, Field> book, List<TocEntry> toc, Integer tocFromUnit, Integer tocToUnit) {
        public boolean hasToc() {
            return toc.size() >= MIN_TOC_ENTRIES;
        }

        public boolean isEmpty() {
            return book.isEmpty() && !hasToc();
        }
    }

    private TextbookExtractor() {
    }

    // ===== 서지 =====

    private static final Pattern ISBN = Pattern.compile(
            "ISBN(?:-1[03])?\\s*[:：]?\\s*((?:97[89][\\s-]?)?(?:\\d[\\s-]?){9}[\\dXx])", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR = Pattern.compile(
            "^\\s*(?:지은이|저자|글쓴이|지음|저\\s|Author(?:s)?|Written by)\\s*[:：|]?\\s*(.{2,80})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHER = Pattern.compile(
            "^\\s*(?:펴낸\\s?곳|출판사|발행처|Publisher|Published by)\\s*[:：|]?\\s*(.{2,80})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "^\\s*(?:도서명|서명|책\\s?제목|Title)\\s*[:：]\\s*(.{2,150})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDITION = Pattern.compile(
            "(개정\\s*\\d{1,2}\\s*판|개정판|제\\s*\\d{1,2}\\s*판|\\d{1,2}\\s*판(?!\\s*\\d*\\s*쇄)|초판"
                    + "|\\d{1,2}(?:st|nd|rd|th)\\s+[Ee]dition|[Ee]dition\\s*\\d{1,2})");

    static Map<String, Field> readBook(List<Unit> units) {
        Map<String, Field> book = new LinkedHashMap<>();
        List<Unit> candidates = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (i < FRONT_UNITS || i >= units.size() - BACK_UNITS) {
                candidates.add(units.get(i));
            }
        }
        for (Unit unit : units) {
            if (!candidates.contains(unit) && unit.text() != null && unit.text().toUpperCase(Locale.ROOT).contains("ISBN")) {
                candidates.add(unit);
            }
        }
        for (Unit unit : candidates) {
            if (unit.text() == null) {
                continue;
            }
            Matcher isbn = ISBN.matcher(unit.text());
            while (!book.containsKey("isbn") && isbn.find()) {
                String digits = isbn.group(1).replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
                if (validIsbn(digits)) {
                    book.put("isbn", new Field(digits, unit.unitNo(), lineAround(unit.text(), isbn.start())));
                }
            }
            for (String rawLine : unit.text().split("\\R")) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.length() > 160) {
                    continue;
                }
                putIfMatch(book, "author", AUTHOR, line, unit);
                putIfMatch(book, "publisher", PUBLISHER, line, unit);
                putIfMatch(book, "title", TITLE, line, unit);
                if (!book.containsKey("edition") && (line.contains("판") || line.toLowerCase(Locale.ROOT).contains("edition"))
                        && looksLikeImprint(line)) {
                    Matcher edition = EDITION.matcher(line);
                    if (edition.find()) {
                        book.put("edition", new Field(edition.group(1).replaceAll("\\s+", " ").trim(), unit.unitNo(), line));
                    }
                }
            }
        }
        return book;
    }

    /** 판 표기는 판권면 줄("개정 3판 1쇄 발행 2021년")에서만 읽는다. 본문의 "2판"·"판단" 같은 말은 버린다. */
    private static boolean looksLikeImprint(String line) {
        return line.contains("발행") || line.contains("펴낸") || line.contains("인쇄") || line.contains("쇄")
                || line.toLowerCase(Locale.ROOT).contains("edition") || line.matches(".*(개정|초판).*");
    }

    private static void putIfMatch(Map<String, Field> book, String key, Pattern pattern, String line, Unit unit) {
        if (book.containsKey(key)) {
            return;
        }
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            String value = m.group(1).replaceAll("\\s{2,}", " ").trim();
            // 이름표 뒤가 비었거나 숫자·기호뿐이면 값이 아니다.
            if (value.replaceAll("[\\p{Punct}\\d\\s]", "").length() >= 2) {
                book.put(key, new Field(value, unit.unitNo(), line));
            }
        }
    }

    static boolean validIsbn(String digits) {
        if (digits.length() == 13 && digits.chars().allMatch(Character::isDigit)) {
            int sum = 0;
            for (int i = 0; i < 12; i++) {
                sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
            }
            return (10 - sum % 10) % 10 == digits.charAt(12) - '0';
        }
        if (digits.length() == 10 && digits.substring(0, 9).chars().allMatch(Character::isDigit)) {
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                sum += (digits.charAt(i) - '0') * (10 - i);
            }
            char last = digits.charAt(9);
            int check = last == 'X' ? 10 : Character.isDigit(last) ? last - '0' : -1;
            return check >= 0 && (sum + check) % 11 == 0;
        }
        return false;
    }

    private static String lineAround(String text, int index) {
        int start = text.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
        int end = text.indexOf('\n', index);
        String line = text.substring(start, end < 0 ? text.length() : end).strip();
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    // ===== 목차 =====

    private static final Pattern TOC_HEAD = Pattern.compile(
            "^\\s*(목\\s*차|차\\s*례|contents|table\\s+of\\s+contents|brief\\s+contents)\\s*$", Pattern.CASE_INSENSITIVE);
    /** 줄 끝의 점선·가운뎃점과 쪽수. */
    private static final String TAIL = "\\s*(?:[.·…‥ㆍ_\\-\\s]{2,})?\\s*(\\d{1,4})?\\s*$";
    private static final Pattern PART_OR_CHAPTER_KO = Pattern.compile(
            "^\\s*(제\\s*\\d{1,2}\\s*[부편장])\\s*[.:]?\\s*(.+?)" + TAIL);
    private static final Pattern CHAPTER_EN = Pattern.compile(
            "^\\s*((?:CHAPTER|Chapter|PART|Part|Unit|UNIT)\\s*\\d{1,2})\\s*[.:-]?\\s*(.+?)" + TAIL);
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(\\d{1,2}(?:\\.\\d{1,2}){0,3})\\s*(?:장|\\.|\\))?\\s+(\\D.*?)" + TAIL);

    static List<TocEntry> readToc(List<Unit> units, int[] range) {
        int head = -1;
        for (int i = 0; i < units.size(); i++) {
            String text = units.get(i).text();
            if (text == null) {
                continue;
            }
            for (String line : text.split("\\R")) {
                if (TOC_HEAD.matcher(line).matches()) {
                    head = i;
                    break;
                }
            }
            if (head >= 0) {
                break;
            }
        }
        if (head < 0) {
            return List.of();
        }
        List<TocEntry> entries = new ArrayList<>();
        int last = head;
        for (int i = head; i < units.size() && i < head + TOC_MAX_UNITS; i++) {
            Unit unit = units.get(i);
            List<TocEntry> found = entriesOf(unit);
            int lines = nonEmptyLines(unit.text());
            // 이어지는 쪽은 줄의 상당수가 목차 모양일 때만 목차로 본다. 본문이 시작되면 멈춘다.
            if (i > head && (found.size() < 3 || found.size() * 10 < lines * 3)) {
                break;
            }
            entries.addAll(found);
            last = i;
            if (entries.size() >= MAX_TOC_ENTRIES) {
                break;
            }
        }
        range[0] = units.get(head).unitNo();
        range[1] = units.get(last).unitNo();
        return entries.size() > MAX_TOC_ENTRIES ? entries.subList(0, MAX_TOC_ENTRIES) : entries;
    }

    private static List<TocEntry> entriesOf(Unit unit) {
        List<TocEntry> out = new ArrayList<>();
        if (unit.text() == null) {
            return out;
        }
        for (String rawLine : unit.text().split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.length() > 140 || TOC_HEAD.matcher(line).matches()) {
                continue;
            }
            TocEntry entry = parseLine(line, unit.unitNo());
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    static TocEntry parseLine(String line, int unitNo) {
        Matcher m = PART_OR_CHAPTER_KO.matcher(line);
        if (m.matches()) {
            String number = m.group(1).replaceAll("\\s+", "");
            int level = number.endsWith("장") ? 1 : 0;
            return entry(level, number, m.group(2), m.group(3), unitNo);
        }
        m = CHAPTER_EN.matcher(line);
        if (m.matches()) {
            String number = m.group(1).replaceAll("\\s+", " ");
            int level = number.toLowerCase(Locale.ROOT).startsWith("part") ? 0 : 1;
            return entry(level, number, m.group(2), m.group(3), unitNo);
        }
        m = NUMBERED.matcher(line);
        if (m.matches()) {
            String number = m.group(1);
            int depth = number.split("\\.").length;
            // "1 제목"은 쪽수가 있을 때만 목차 항목으로 본다(본문 번호 목록과 섞이지 않게). "1.2 제목"은 번호만으로 충분하다.
            if (depth == 1 && m.group(3) == null) {
                return null;
            }
            return entry(depth, number, m.group(2), m.group(3), unitNo);
        }
        return null;
    }

    private static TocEntry entry(int level, String number, String rawTitle, String page, int unitNo) {
        String title = rawTitle == null ? "" : rawTitle.replaceAll("[.·…‥ㆍ_]{2,}.*$", "").strip();
        if (title.length() < 2 || title.replaceAll("[\\p{Punct}\\d\\s]", "").isEmpty()) {
            return null;
        }
        Integer printed = page == null ? null : Integer.valueOf(page);
        return new TocEntry(Math.max(0, level), number, title.length() > 120 ? title.substring(0, 120) : title, printed, unitNo);
    }

    private static int nonEmptyLines(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }

    public static Result extract(List<Unit> units) {
        int[] range = {0, 0};
        List<TocEntry> toc = readToc(units, range);
        Map<String, Field> book = readBook(units);
        boolean found = toc.size() >= MIN_TOC_ENTRIES;
        return new Result(book, found ? toc : List.of(), found ? range[0] : null, found ? range[1] : null);
    }
}
