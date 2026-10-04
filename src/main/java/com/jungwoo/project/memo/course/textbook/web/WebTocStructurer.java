package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 페이지에서 잘라 낸 목차 원문을 장·절 항목으로 나눈다.
 *
 * <p>먼저 규칙({@link TextbookExtractor#parseLine})으로 읽는다. 규칙으로 못 읽은 줄이 많으면 모델에게 <b>줄 번호와 수준만</b>
 * 고르게 할 수 있다({@link #fromModelPicks}) — 제목은 서버가 그 줄 원문에서 잘라 쓰므로 모델이 없는 장·절을 만들 수 없다.
 * 서버는 줄 번호 범위·중복·순서·깊이·길이를 검사하고 어긋난 것은 버린다.
 *
 * <p>coverage는 "이 페이지에 실린 목차를 얼마나 읽었나"다. 책 전체 목차를 확보했다는 증명이 아니다.
 */
public final class WebTocStructurer {

    static final int MAX_ENTRIES = 400;
    static final int MAX_TITLE = 120;
    static final double FULL_RATIO = 0.9;

    private WebTocStructurer() {
    }

    public record Structured(List<TextbookExtractor.TocEntry> entries, String coverage, int lines, int readLines,
                             String method) {
    }

    /** 원문 줄(빈 줄 제외). 모델에 줄 번호로 보여 줄 때도 이 목록을 쓴다. */
    public static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\\R")) {
            String c = BookPageParser.clean(line, 300);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** 규칙으로 읽는다. 항목의 unit은 원문 줄 번호(1부터)다. */
    public static Structured byRules(String raw, boolean pageTruncated) {
        List<String> lines = lines(raw);
        if (lines.isEmpty()) {
            return new Structured(List.of(), "NONE", 0, 0, "RULE");
        }
        List<TextbookExtractor.TocEntry> entries = new ArrayList<>();
        int skippedHeads = 0;
        for (int i = 0; i < lines.size() && entries.size() < MAX_ENTRIES; i++) {
            String line = lines.get(i);
            if (isHeadOnly(line)) {
                skippedHeads++;
                continue;
            }
            TextbookExtractor.TocEntry entry = TextbookExtractor.parseLine(line, i + 1);
            if (entry != null) {
                entries.add(sanitize(entry));
            }
        }
        int considered = lines.size() - skippedHeads;
        String coverage = coverageOf(entries.size(), considered, pageTruncated);
        return new Structured(entries, coverage, considered, entries.size(), "RULE");
    }

    static String coverageOf(int read, int considered, boolean truncated) {
        if (read == 0) {
            return considered == 0 ? "NONE" : "UNKNOWN";
        }
        if (truncated) {
            return "PARTIAL";
        }
        double ratio = considered == 0 ? 0 : (double) read / considered;
        return ratio >= FULL_RATIO ? "PAGE_FULL" : "PARTIAL";
    }

    /** 모델이 고른 줄. line은 {@link #lines} 기준 1부터, level은 0(부)·1(장)·2(절). */
    public record Pick(int line, int level) {
    }

    /**
     * 모델이 고른 줄 번호로 항목을 만든다. 제목·번호·쪽은 서버가 원문 줄에서 읽는다.
     * 범위 밖·중복·역순·깊이 밖 번호는 버린다. 모델이 고른 결과는 규칙 확인이 아니므로 coverage는 PAGE_FULL이 되지 않는다.
     */
    public static Structured fromModelPicks(String raw, List<Pick> picks, boolean pageTruncated) {
        List<String> lines = lines(raw);
        List<TextbookExtractor.TocEntry> entries = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int last = 0;
        for (Pick pick : picks == null ? List.<Pick>of() : picks) {
            if (pick == null || pick.line() < 1 || pick.line() > lines.size() || pick.line() <= last
                    || pick.level() < 0 || pick.level() > 2 || !seen.add(pick.line())) {
                continue;
            }
            last = pick.line();
            String line = lines.get(pick.line() - 1);
            TextbookExtractor.TocEntry ruled = TextbookExtractor.parseLine(line, pick.line());
            TextbookExtractor.TocEntry entry = ruled != null
                    ? new TextbookExtractor.TocEntry(pick.level(), ruled.number(), ruled.title(), ruled.page(), pick.line())
                    : plain(line, pick.level(), pick.line());
            if (entry != null) {
                entries.add(sanitize(entry));
            }
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
        }
        String coverage = entries.isEmpty() ? "UNKNOWN" : pageTruncated ? "PARTIAL" : "UNKNOWN";
        return new Structured(entries, coverage, lines.size(), entries.size(), "MODEL_PICK");
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^((?:제\\s*)?\\d{1,2}(?:\\.\\d{1,2}){0,3}\\s*[장부편절]?|[IVX]{1,4}\\.|[A-Z]\\.)\\s+(.+)$");
    private static final Pattern TRAILING_PAGE = Pattern.compile("\\s*(?:[.·…‥ㆍ_\\-/\\s]{2,}|\\s)(?:p\\.\\s*)?(\\d{1,4})\\s*$");

    /** 번호 규칙에 안 맞는 줄(예: "Appendix", "부록 A 정답")을 그대로 항목으로. */
    private static TextbookExtractor.TocEntry plain(String line, int level, int lineNo) {
        String rest = line;
        Integer page = null;
        Matcher p = TRAILING_PAGE.matcher(rest);
        if (p.find()) {
            page = Integer.valueOf(p.group(1));
            rest = rest.substring(0, p.start());
        }
        String number = null;
        Matcher n = LEADING_NUMBER.matcher(rest);
        if (n.matches()) {
            number = n.group(1).strip();
            rest = n.group(2);
        }
        String title = rest.strip();
        if (title.replaceAll("[\\p{Punct}\\d\\s]", "").length() < 2) {
            return null;
        }
        return new TextbookExtractor.TocEntry(level, number, title, page, lineNo);
    }

    /** 제어 문자 제거·길이 자르기. 원문 그대로가 아니라 저장·프롬프트에 쓸 수 있는 모양으로만 바꾼다. */
    static TextbookExtractor.TocEntry sanitize(TextbookExtractor.TocEntry e) {
        String title = BookPageParser.clean(e.title(), MAX_TITLE);
        String number = BookPageParser.clean(e.number(), 20);
        Integer page = e.page() != null && e.page() > 0 && e.page() < 5000 ? e.page() : null;
        return new TextbookExtractor.TocEntry(Math.max(0, Math.min(3, e.level())), number, title == null ? "" : title,
                page, e.unit());
    }

    private static boolean isHeadOnly(String line) {
        return line.matches("(?i)^\\s*(목\\s*차|차\\s*례|contents|table of contents)\\s*$");
    }
}
