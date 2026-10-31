package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 페이지에서 잘라 낸 목차 원문을 장·절 항목으로 나눈다.
 *
 * <p>먼저 규칙으로 읽는다({@link #byRules}). 웹 목차는 목차 블록만 잘라 온 것이라 본문 번호 목록과 섞일 걱정이 적다 — 쪽 번호 없는
 * "01 제목"도 절로 읽고, "실습 1-1"·"요약"·"연습문제"·"부록 A." 같은 하위 표지도 읽는다(업로드 자료의 규칙
 * {@link TextbookExtractor#parseLine}은 그대로다). 규칙이 못 읽은 줄이 남으면 모델에게 <b>그 줄들의 번호와 깊이만</b> 고르게 할 수
 * 있다({@link #withModelPicks}) — 제목은 서버가 그 줄 원문에서 잘라 쓰므로 모델이 없는 장·절을 만들 수 없고, 규칙이 읽은 항목의
 * 제목·깊이·순서·부모는 바뀌지 않는다.
 *
 * <p>항목의 unit은 <b>원문 줄 번호</b>({@link #lines} 기준 1부터)다. 같은 원문이면 규칙이 바뀌어도 같은 줄 번호다 — 목차 항목의
 * 열쇠로 쓴다.
 *
 * <p>coverage는 "이 페이지에 실린 목차를 얼마나 읽었나"다. 책 전체 목차를 확보했다는 증명이 아니다.
 */
public final class WebTocStructurer {

    /**
     * 구조화 규칙의 판. 바뀌면 저장된 원문으로 다시 구조화한다(페이지를 다시 받지 않는다).
     * 2(2026-10-06): 쪽 번호 없는 절, 실습·요약·연습문제·부록, 장 아래 절의 깊이, 못 읽은 줄의 모델 보완, 깊이 8까지.
     */
    public static final int TOC_VERSION = 2;
    static final int MAX_ENTRIES = 400;
    static final int MAX_TITLE = 120;
    /** 깊이 안전 상한(0부터). 규칙은 4를 넘지 않는다 — 모델 보완이 끝없이 깊은 트리를 만들지 못하게 하는 장치다. */
    public static final int MAX_LEVEL = 8;
    static final double FULL_RATIO = 0.9;

    private WebTocStructurer() {
    }

    /**
     * @param unread 규칙(과 모델 보완)으로 목차 항목으로도, 머리 줄로도 읽지 못한 줄 수
     */
    public record Structured(List<TextbookExtractor.TocEntry> entries, String coverage, int lines, int readLines,
                             String method, int unread) {

        public Structured(List<TextbookExtractor.TocEntry> entries, String coverage, int lines, int readLines,
                          String method) {
            this(entries, coverage, lines, readLines, method, Math.max(0, lines - readLines));
        }
    }

    /** 원문 줄(빈 줄 제외). 모델에 줄 번호로 보여 줄 때도, 항목 열쇠(줄 번호)도 이 목록을 쓴다. */
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

    // ===== 규칙 =====

    enum Kind { PART, CHAPTER, SECTION_HEAD, NUMBERED, LABELED, APPENDIX }

    /** 한 줄을 읽은 결과(깊이는 목차 전체를 본 뒤 정한다). depth는 NUMBERED의 번호 깊이(1.2.3 → 3). */
    record Read(Kind kind, String number, String title, Integer page, int depth, int line) {
    }

    /** 줄 끝의 점선·가운뎃점·슬래시와 쪽수. */
    /**
     * 쪽 번호는 구분자가 있을 때만 뗀다: 공백 뒤 점선·가운뎃점·슬래시(" / 6", " ..... 12"), 붙은 점선("…12"), 두 칸 이상 공백, "p.".
     * 한 칸 공백이나 붙은 숫자는 제목의 일부다("01 IPv6", "표준 C99", "윈도우 10", "버전 1.2").
     */
    private static final String PAGE_SEP = "(?:\\.?\\s+[.·…‥ㆍ_\\-/][.·…‥ㆍ_\\-/\\s]*|[.·…‥ㆍ_]{2,}\\s*|\\s{2,}|\\s*p\\.\\s*)";
    private static final String TAIL = "(?:" + PAGE_SEP + "(\\d{1,4}))?\\s*$";
    private static final Pattern PART_KO = Pattern.compile("^\\s*(제\\s*\\d{1,2}\\s*[부편])\\s*[.:]?\\s*(.+?)" + TAIL);
    private static final Pattern CHAPTER_KO = Pattern.compile("^\\s*(제\\s*\\d{1,2}\\s*장|\\d{1,2}장)\\s*[.:]?\\s*(.+?)" + TAIL);
    private static final Pattern PART_EN = Pattern.compile("^\\s*((?:PART|Part)\\s*\\d{1,2})\\s*[.:-]?\\s*(.+?)" + TAIL);
    private static final Pattern CHAPTER_EN = Pattern.compile(
            "^\\s*((?:CHAPTER|Chapter|Unit|UNIT|Lesson|LESSON)\\s*\\d{1,2})\\s*[.:-]?\\s*(.+?)" + TAIL);
    private static final Pattern SECTION_EN = Pattern.compile("^\\s*((?:SECTION|Section)\\s*\\d{1,2})\\s*[.:-]?\\s*(.+?)" + TAIL);
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(\\d{1,2}(?:\\.\\d{1,2}){0,3})\\s*(?:\\.|\\))?\\s+(\\D.*?)" + TAIL);
    private static final Pattern LAB = Pattern.compile(
            "^\\s*((?:실습|Lab|LAB)\\s*\\d{1,2}(?:[-.]\\d{1,2})?)\\s*[.:)]?\\s*(.*?)" + TAIL);
    /** 장 끝의 요약·문제 묶음. "요약/연습문제"처럼 둘을 한 줄에 적기도 한다. */
    private static final String LABEL_WORD = "(?:요약|정리|핵심\\s*정리|단원\\s*정리|연습\\s*문제|확인\\s*문제|도전\\s*문제|종합\\s*문제"
            + "|실전\\s*문제|Summary|SUMMARY|Exercises?|EXERCISES?|Review\\s+Questions|Quiz|QUIZ|Problems)";
    private static final Pattern LABEL = Pattern.compile(
            "^\\s*(" + LABEL_WORD + "(?:\\s*(?:[/&·,+]|및)\\s*" + LABEL_WORD + ")*)(?![가-힣A-Za-z])\\s*[.:)]?\\s*(.*?)"
                    + TAIL);
    private static final Pattern APPENDIX = Pattern.compile(
            "^\\s*((?:부록|Appendix|APPENDIX)(?:\\s*(?:[A-Z](?![A-Za-z])|\\d{1,2}))?)(?![가-힣A-Za-z])\\s*[.:)]?\\s*(.*?)"
                    + TAIL);

    static Read readLine(String line, int lineNo) {
        Matcher m = PART_KO.matcher(line);
        if (m.matches()) {
            return read(Kind.PART, m.group(1).replaceAll("\\s+", ""), m.group(2), m.group(3), 0, lineNo);
        }
        m = CHAPTER_KO.matcher(line);
        if (m.matches()) {
            return read(Kind.CHAPTER, m.group(1).replaceAll("\\s+", ""), m.group(2), m.group(3), 0, lineNo);
        }
        m = PART_EN.matcher(line);
        if (m.matches()) {
            return read(Kind.PART, m.group(1).replaceAll("\\s+", " "), m.group(2), m.group(3), 0, lineNo);
        }
        m = CHAPTER_EN.matcher(line);
        if (m.matches()) {
            return read(Kind.CHAPTER, m.group(1).replaceAll("\\s+", " "), m.group(2), m.group(3), 0, lineNo);
        }
        m = SECTION_EN.matcher(line);
        if (m.matches()) {
            return read(Kind.SECTION_HEAD, m.group(1).replaceAll("\\s+", " "), m.group(2), m.group(3), 0, lineNo);
        }
        m = LAB.matcher(line);
        if (m.matches()) {
            return labeled(m.group(1).replaceAll("\\s+", " "), m.group(2), m.group(3), lineNo);
        }
        m = LABEL.matcher(line);
        if (m.matches()) {
            return labeled(m.group(1).replaceAll("\\s*([/&·,+])\\s*", "$1").replaceAll("\\s+", " "), m.group(2),
                    m.group(3), lineNo);
        }
        m = APPENDIX.matcher(line);
        if (m.matches()) {
            String number = m.group(1).replaceAll("\\s+", " ");
            String rest = m.group(2) == null ? "" : m.group(2).strip();
            return rest.isEmpty() ? read(Kind.APPENDIX, null, number, m.group(3), 0, lineNo)
                    : read(Kind.APPENDIX, number, rest, m.group(3), 0, lineNo);
        }
        m = NUMBERED.matcher(line);
        if (m.matches()) {
            String number = m.group(1);
            return read(Kind.NUMBERED, number, m.group(2), m.group(3), number.split("\\.").length, lineNo);
        }
        return null;
    }

    /** "실습 1-1 제목" → 번호 "실습 1-1", 제목 "제목". "요약"처럼 이름표뿐이면 이름표가 제목이다. */
    private static Read labeled(String label, String rest, String page, int lineNo) {
        String r = rest == null ? "" : rest.strip();
        return r.isEmpty() ? read(Kind.LABELED, null, label, page, 0, lineNo) : read(Kind.LABELED, label, r, page, 0, lineNo);
    }

    private static Read read(Kind kind, String number, String rawTitle, String page, int depth, int lineNo) {
        String title = rawTitle == null ? "" : rawTitle.replaceAll("[.·…‥ㆍ_]{2,}.*$", "")
                .replaceAll("\\s*/\\s*$", "").strip();
        if (title.length() < 2 || title.replaceAll("[\\p{Punct}\\d\\s]", "").isEmpty()) {
            return null;
        }
        Integer printed = page == null ? null : Integer.valueOf(page);
        return new Read(kind, number, title, printed, depth, lineNo);
    }

    /** 규칙으로 읽는다. 항목의 unit은 원문 줄 번호(1부터)다. */
    public static Structured byRules(String raw, boolean pageTruncated) {
        List<String> lines = lines(raw);
        if (lines.isEmpty()) {
            return new Structured(List.of(), "NONE", 0, 0, "RULE", 0);
        }
        List<Read> reads = new ArrayList<>();
        int skippedHeads = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isHeadOnly(line)) {
                skippedHeads++;
                continue;
            }
            Read r = readLine(line, i + 1);
            if (r != null) {
                reads.add(r);
            }
        }
        List<TextbookExtractor.TocEntry> entries = leveled(reads);
        // 상한을 넘는 줄은 목차에 넣지 않는다 — 읽지 못한 줄로 세고 PARTIAL로 남긴다(목차에 남았다고 하지 않는다).
        boolean cut = pageTruncated || entries.size() > MAX_ENTRIES;
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        int considered = lines.size() - skippedHeads;
        int unread = Math.max(0, considered - entries.size());
        String coverage = coverageOf(entries.size(), considered, cut);
        return new Structured(entries, coverage, considered, entries.size(), "RULE", unread);
    }

    /**
     * 읽은 줄에 깊이를 매긴다. 부 0, 장·부록 1, 절은 장이 있으면 1 + 번호 상대 깊이, 없으면 상대 깊이.
     * 상대 깊이는 <b>장 구간마다</b> 가장 얕은 번호를 1로 본다(한 장은 "01", 다른 장은 "1.1"을 써도 각각 절이다).
     * 부·장·부록을 만나면 구간과 "지금 장"을 새로 시작한다.
     * 실습·요약·연습문제는 지금 장(장이 없으면 가장 얕은 번호 항목, 그것도 없으면 부)의 자식이다 — 원문에 들여쓰기가 없고,
     * "실습 1-1"은 장 번호를 단다.
     */
    static List<TextbookExtractor.TocEntry> leveled(List<Read> reads) {
        boolean hasChapter = reads.stream().anyMatch(r -> r.kind() == Kind.CHAPTER);
        // 구간(부·장·부록이 연다)마다 번호 깊이 최솟값
        int[] segmentOf = new int[reads.size()];
        List<Integer> segmentMin = new ArrayList<>();
        segmentMin.add(Integer.MAX_VALUE);
        for (int i = 0; i < reads.size(); i++) {
            Kind k = reads.get(i).kind();
            if (i > 0 && (k == Kind.PART || k == Kind.CHAPTER || k == Kind.APPENDIX)) {
                segmentMin.add(Integer.MAX_VALUE);
            }
            int seg = segmentMin.size() - 1;
            segmentOf[i] = seg;
            if (k == Kind.NUMBERED) {
                segmentMin.set(seg, Math.min(segmentMin.get(seg), reads.get(i).depth()));
            }
        }
        List<TextbookExtractor.TocEntry> out = new ArrayList<>();
        Integer anchorLevel = null; // 실습·요약이 붙을 항목의 깊이
        for (int i = 0; i < reads.size(); i++) {
            Read r = reads.get(i);
            int minDepth = segmentMin.get(segmentOf[i]);
            int level = switch (r.kind()) {
                case PART -> 0;
                case CHAPTER, APPENDIX -> 1;
                case SECTION_HEAD -> hasChapter ? 2 : 1;
                case NUMBERED -> (hasChapter ? 1 : 0) + (r.depth() - minDepth + 1);
                case LABELED -> anchorLevel == null ? 1 : anchorLevel + 1;
            };
            switch (r.kind()) {
                case PART -> anchorLevel = 0;
                case CHAPTER, APPENDIX -> anchorLevel = 1;
                case SECTION_HEAD -> anchorLevel = hasChapter ? anchorLevel : Integer.valueOf(1);
                case NUMBERED -> anchorLevel = !hasChapter && r.depth() == minDepth ? Integer.valueOf(level) : anchorLevel;
                default -> {
                }
            }
            out.add(sanitize(new TextbookExtractor.TocEntry(level, r.number(), r.title(), r.page(), r.line())));
        }
        return out;
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

    // ===== 모델 보완 =====

    /** 모델이 고른 줄. line은 {@link #lines} 기준 1부터, level은 0(부)부터 {@link #MAX_LEVEL}까지. */
    public record Pick(int line, int level) {
    }

    /** 규칙으로 못 읽은 줄 번호(머리 줄 제외). 모델에 고르게 할 후보다. */
    public static Set<Integer> unreadLines(String raw, Structured ruled) {
        List<String> lines = lines(raw);
        Set<Integer> read = new HashSet<>();
        ruled.entries().forEach(e -> read.add(e.unit()));
        Set<Integer> out = new java.util.TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!read.contains(i + 1) && !isHeadOnly(lines.get(i))) {
                out.add(i + 1);
            }
        }
        return out;
    }

    /** 규칙 없이 모델이 고른 줄만으로 만든다(규칙이 하나도 못 읽은 경우). */
    public static Structured fromModelPicks(String raw, List<Pick> picks, boolean pageTruncated) {
        return withModelPicks(raw, new Structured(List.of(), "UNKNOWN", lines(raw).size(), 0, "RULE"), picks,
                pageTruncated);
    }

    /**
     * 규칙 결과에 모델이 고른 줄을 보탠다. 제목·번호·쪽은 서버가 원문 줄에서 읽는다.
     * <ul>
     *   <li>규칙이 읽은 줄·범위 밖·중복·역순 번호는 버린다. 규칙 항목은 그대로다.</li>
     *   <li>원문 줄 순서로 합친다. 모델 항목의 깊이는 바로 앞 항목 깊이 + 1을 넘지 않는다(부모 없는 깊은 항목을 만들지 않는다).</li>
     *   <li>모델 항목 뒤에 오는 규칙 항목의 부모가 모델 항목으로 바뀌지 않게, 모델 항목의 깊이는 다음 규칙 항목 깊이보다 얕지 않게 올린다.</li>
     * </ul>
     * 모델이 고른 결과는 규칙 확인이 아니므로 coverage는 PAGE_FULL이 되지 않는다(규칙만으로 읽은 범위를 그대로 둔다).
     */
    public static Structured withModelPicks(String raw, Structured ruled, List<Pick> picks, boolean pageTruncated) {
        List<String> lines = lines(raw);
        Map<Integer, TextbookExtractor.TocEntry> byLine = new TreeMap<>();
        Set<Integer> ruleLines = new HashSet<>();
        for (TextbookExtractor.TocEntry e : ruled.entries()) {
            byLine.put(e.unit(), e);
            ruleLines.add(e.unit());
        }
        Map<Integer, Integer> picked = new TreeMap<>();
        int last = 0;
        for (Pick pick : picks == null ? List.<Pick>of() : picks) {
            if (pick == null || pick.line() < 1 || pick.line() > lines.size() || pick.line() <= last
                    || pick.level() < 0 || pick.level() > MAX_LEVEL || ruleLines.contains(pick.line())
                    || isHeadOnly(lines.get(pick.line() - 1))) {
                continue;
            }
            last = pick.line();
            picked.put(pick.line(), pick.level());
        }
        // 규칙 항목이 먼저 자리를 차지한다. 모델 항목은 남은 자리만큼(원문 앞쪽부터) — 모델이 규칙 항목을 상한 밖으로 밀어내지 못한다.
        int room = Math.max(0, MAX_ENTRIES - ruled.entries().size());
        while (picked.size() > room) {
            ((TreeMap<Integer, Integer>) picked).pollLastEntry();
        }
        if (picked.isEmpty()) {
            return ruled;
        }
        List<Integer> order = new ArrayList<>(byLine.keySet());
        order.addAll(picked.keySet());
        order.sort(Integer::compare);
        List<TextbookExtractor.TocEntry> out = new ArrayList<>();
        int added = 0;
        for (int i = 0; i < order.size(); i++) {
            int line = order.get(i);
            TextbookExtractor.TocEntry rule = byLine.get(line);
            if (rule != null) {
                out.add(rule);
                continue;
            }
            // 아래쪽: 뒤따르는 첫 규칙 항목보다 얕으면 그 항목의 부모를 빼앗는다. 위쪽: 앞 항목의 자식까지(단, 아래쪽보다 낮출 수는 없다).
            int prev = out.isEmpty() ? -1 : out.get(out.size() - 1).level();
            int low = 0;
            for (int j = i + 1; j < order.size(); j++) {
                TextbookExtractor.TocEntry next = byLine.get(order.get(j));
                if (next != null) {
                    low = next.level();
                    break;
                }
            }
            int high = Math.max(prev + 1, low);
            int level = Math.max(low, Math.min(high, picked.get(line)));
            TextbookExtractor.TocEntry entry = modelEntry(lines.get(line - 1), level, line);
            if (entry != null) {
                out.add(entry);
                added++;
            }
        }
        if (added == 0) {
            return ruled;
        }
        int considered = ruled.lines();
        int unread = Math.max(0, considered - out.size()); // 최종으로 남은 항목 기준
        String coverage = ruled.entries().isEmpty()
                ? (pageTruncated ? "PARTIAL" : "UNKNOWN")
                : ruled.coverage();
        return new Structured(out, coverage, considered, out.size(), ruled.entries().isEmpty() ? "MODEL_PICK" : "RULE_MODEL",
                unread);
    }

    private static TextbookExtractor.TocEntry modelEntry(String line, int level, int lineNo) {
        Read ruled = readLine(line, lineNo);
        if (ruled != null) {
            return sanitize(new TextbookExtractor.TocEntry(level, ruled.number(), ruled.title(), ruled.page(), lineNo));
        }
        TextbookExtractor.TocEntry plain = plain(line, level, lineNo);
        return plain == null ? null : sanitize(plain);
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^((?:제\\s*)?\\d{1,2}(?:\\.\\d{1,2}){0,3}\\s*[장부편절]?|[IVX]{1,4}\\.|[A-Z]\\.)\\s+(.+)$");
    private static final Pattern TRAILING_PAGE = Pattern.compile(PAGE_SEP + "(\\d{1,4})\\s*$");

    /** 번호 규칙에 안 맞는 줄(예: "Appendix", "Part I 기초")을 그대로 항목으로. */
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
        return new TextbookExtractor.TocEntry(Math.max(0, e.level()), number,
                title == null ? "" : title, page, e.unit());
    }

    static boolean isHeadOnly(String line) {
        return line.toLowerCase(Locale.ROOT).matches("^\\s*(목\\s*차|차\\s*례|contents|table of contents)\\s*$");
    }
}
