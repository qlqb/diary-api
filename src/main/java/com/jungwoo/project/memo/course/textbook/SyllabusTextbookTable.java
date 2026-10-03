package com.jungwoo.project.memo.course.textbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 강의계획서의 교재 칸을 규칙으로 읽는다. 모델을 부르지 않는다.
 *
 * <p>강의계획서 PDF의 표는 텍스트로 뽑으면 칸이 줄로 흩어진다. 실제 모양(2026-10-04, 합성으로 재현):
 * <pre>
 * 도서명 저자 출판사 비고
 *  Michael
 *  주교재  NEW English Conversation Arts 1  형설출판사
 * Putlack, 이현호
 * </pre>
 * 머리 줄(도서명·저자·출판사 중 둘 이상)을 찾고, 그 아래 몇 줄에서 역할어(주교재·부교재·참고)가 있는 줄을 책 하나로 본다.
 * 그 줄은 두 칸 이상의 공백으로 칸을 나누고, 저자 칸이 비면 바로 위·아래의 짧은 줄을 저자의 줄바꿈으로 본다.
 * "주교재: 제목 / 저자 / 출판사"처럼 이름표로 적은 줄도 읽는다.
 *
 * <p>못 읽으면 비운다 — 표가 더 흩어진 경우는 조회 작업이 모델 보조로 읽고, 그 값도 원문에 있는지 서버가 확인한다.
 */
final class SyllabusTextbookTable {

    private static final Pattern TITLE_LABEL = Pattern.compile("(도서명|교재명|교재\\s명|서\\s?명|책\\s?제목|Title)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_LABEL = Pattern.compile("(저\\s?자|지은이|Author)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHER_LABEL = Pattern.compile("(출판사|발행처|출\\s?판|Publisher)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ROLE = Pattern.compile(
            "(주\\s?교재|부\\s?교재|보조\\s?교재|참고\\s?교재|참고\\s?도서|참고\\s?문헌|교과서|Main\\s+Textbook|Textbook"
                    + "|Required\\s+Text|Reference)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_LABELLED = Pattern.compile(
            "^\\s*(주\\s?교재|부\\s?교재|보조\\s?교재|참고\\s?교재|참고\\s?도서|교재|Textbook|Main\\s+Textbook)\\s*[:：]\\s*(.{2,200})$",
            Pattern.CASE_INSENSITIVE);
    /** 표가 끝났다고 볼 다음 칸 머리. */
    private static final Pattern NEXT_SECTION = Pattern.compile(
            "^\\s*(성적|평가|수업\\s?시|사용\\s?도구|수강\\s?안내|주차|강의\\s?계획|학습\\s?목표|과목\\s?개요|담당\\s?교수|교육\\s?목표)");
    private static final Pattern PUBLISHER_LIKE = Pattern.compile(
            "(출판사?|출판부|북스|BOOKS|Press|프레스|아카데미|에듀|교육|미디어|문화사|사이언스|퍼블리싱|Publishing|Pearson|Wiley|"
                    + "McGraw|O'Reilly|Cengage|Springer|\\(주\\)|㈜|사)$", Pattern.CASE_INSENSITIVE);
    /** 한 낱말짜리 출판사 이름(끝이 출판사·출판부·북스·프레스·아카데미·에듀·미디어·퍼블리싱·Press). */
    private static final Pattern PUBLISHER_TAIL = Pattern.compile(
            "[\\p{L}\\p{N}()㈜]{1,20}(출판사|출판부|북스|프레스|아카데미|에듀|미디어|퍼블리싱|Press|Books)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISBN = Pattern.compile("(97[89][\\d\\-\\s]{10,16}\\d|\\d[\\d\\-\\s]{8,11}[\\dXx])");

    private static final int MAX_REGION = 10;
    private static final int MAX_BOOKS = 6;

    private SyllabusTextbookTable() {
    }

    static List<TextbookExtractor.BookClue> read(List<TextbookExtractor.Unit> units) {
        List<TextbookExtractor.BookClue> out = new ArrayList<>();
        for (TextbookExtractor.Unit unit : units) {
            if (unit.text() == null || out.size() >= MAX_BOOKS) {
                continue;
            }
            String[] lines = unit.text().split("\\R");
            for (int h = 0; h < lines.length && out.size() < MAX_BOOKS; h++) {
                if (isHeader(lines[h])) {
                    readTable(lines, h, unit.unitNo(), out);
                }
                Matcher labelled = ROLE_LABELLED.matcher(lines[h]);
                if (labelled.matches()) {
                    TextbookExtractor.BookClue clue = fromLabelled(roleOf(labelled.group(1)), labelled.group(2),
                            unit.unitNo(), lines[h].strip());
                    if (clue != null && out.stream().noneMatch(c -> sameTitle(c.title(), clue.title()))) {
                        out.add(clue);
                    }
                }
            }
        }
        return out;
    }

    /** 교재 표 머리가 있는 단위와 줄 번호(0부터). 규칙이 책을 못 읽었을 때 모델 보조가 볼 창의 중심이다. */
    record HeaderAt(int unitNo, int line) {
    }

    static HeaderAt findHeader(List<TextbookExtractor.Unit> units) {
        for (TextbookExtractor.Unit unit : units) {
            if (unit.text() == null) {
                continue;
            }
            String[] lines = unit.text().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (isHeader(lines[i])) {
                    return new HeaderAt(unit.unitNo(), i);
                }
            }
        }
        return null;
    }

    static boolean isHeader(String line) {
        if (line == null || line.length() > 80) {
            return false;
        }
        int hits = 0;
        hits += TITLE_LABEL.matcher(line).find() ? 1 : 0;
        hits += AUTHOR_LABEL.matcher(line).find() ? 1 : 0;
        hits += PUBLISHER_LABEL.matcher(line).find() ? 1 : 0;
        return hits >= 2;
    }

    private static void readTable(String[] lines, int header, int unitNo, List<TextbookExtractor.BookClue> out) {
        int end = Math.min(lines.length, header + 1 + MAX_REGION);
        for (int i = header + 1; i < end; i++) {
            if (NEXT_SECTION.matcher(lines[i]).find() || isHeader(lines[i])) {
                end = i;
                break;
            }
        }
        for (int i = header + 1; i < end && out.size() < MAX_BOOKS; i++) {
            Matcher role = ROLE.matcher(lines[i]);
            if (!role.find()) {
                continue;
            }
            String rest = (lines[i].substring(0, role.start()) + "  " + lines[i].substring(role.end())).strip();
            if (rest.startsWith(":") || rest.startsWith("：")) {
                rest = rest.substring(1).strip();
            }
            List<String> cells = new ArrayList<>();
            for (String cell : rest.split("\\s{2,}|\\t")) {
                if (!cell.isBlank()) {
                    cells.add(cell.strip());
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            if (cells.size() == 1) {
                // 칸 사이가 한 칸 공백으로만 뽑힌 표: 끝 낱말이 출판사 모양이면 떼어 낸다("… Arts 1 형설출판사").
                String one = cells.get(0);
                int space = one.lastIndexOf(' ');
                if (space > 0 && PUBLISHER_TAIL.matcher(one.substring(space + 1)).matches()) {
                    cells = new ArrayList<>(List.of(one.substring(0, space).strip(), one.substring(space + 1)));
                }
            }
            String title = cells.get(0);
            String publisher = null;
            String author = null;
            if (cells.size() >= 3) {
                author = String.join(", ", cells.subList(1, cells.size() - 1));
                publisher = cells.get(cells.size() - 1);
            } else if (cells.size() == 2) {
                if (PUBLISHER_LIKE.matcher(cells.get(1)).find()) {
                    publisher = cells.get(1);
                } else {
                    author = cells.get(1);
                }
            }
            StringBuilder quote = new StringBuilder(lines[i].strip());
            if (author == null) {
                // 저자 칸이 줄바꿈으로 위·아래에 흩어졌다(" Michael" / "Putlack, 이현호").
                String above = i - 1 > header ? wrapped(lines[i - 1]) : null;
                String below = i + 1 < end ? wrapped(lines[i + 1]) : null;
                if (above != null || below != null) {
                    author = ((above == null ? "" : above) + " " + (below == null ? "" : below)).strip();
                    quote = new StringBuilder((above == null ? "" : lines[i - 1].strip() + " / ") + lines[i].strip()
                            + (below == null ? "" : " / " + lines[i + 1].strip()));
                }
            }
            TextbookExtractor.BookClue clue = build(roleOf(role.group(1)), title, author, publisher, unitNo,
                    quote.toString());
            if (clue != null && out.stream().noneMatch(c -> sameTitle(c.title(), clue.title()))) {
                out.add(clue);
            }
        }
    }

    /** 다른 칸이 아닌 짧은 줄 — 저자 이름의 줄바꿈일 수 있다. 역할어·숫자뿐·머리 줄은 아니다. */
    private static String wrapped(String line) {
        String v = line == null ? "" : line.strip();
        if (v.isEmpty() || v.length() > 40 || ROLE.matcher(v).find() || isHeader(v) || NEXT_SECTION.matcher(v).find()
                || v.replaceAll("[\\d\\s.,\\-]", "").isEmpty()) {
            return null;
        }
        return v;
    }

    private static TextbookExtractor.BookClue fromLabelled(String role, String body, int unitNo, String quote) {
        String[] parts = body.split("\\s*/\\s*|\\s*,\\s*(?=[^,]*(출판|Press|북스))");
        String title = parts[0];
        String author = parts.length >= 3 ? parts[1] : null;
        String publisher = parts.length >= 2 ? parts[parts.length - 1] : null;
        if (publisher != null && author == null && !PUBLISHER_LIKE.matcher(publisher).find()) {
            author = publisher;
            publisher = null;
        }
        return build(role, title, author, publisher, unitNo, quote);
    }

    private static TextbookExtractor.BookClue build(String role, String rawTitle, String author, String publisher,
                                                    int unitNo, String quote) {
        String title = rawTitle == null ? null : rawTitle.replaceAll("^[\\-•·*]+", "").strip();
        String isbn = null;
        if (title != null) {
            Matcher m = ISBN.matcher(title);
            if (m.find()) {
                String digits = m.group(1).replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
                if (TextbookExtractor.isValidIsbn(digits)) {
                    isbn = digits;
                    title = (title.substring(0, m.start()) + title.substring(m.end())).replaceAll("ISBN\\s*[:：]?", "").strip();
                }
            }
        }
        if (title == null || title.replaceAll("[\\p{Punct}\\d\\s]", "").length() < 2 || title.length() > 200) {
            return null;
        }
        String edition = null;
        Matcher ed = Pattern.compile("[(\\[]?\\s*(개정\\s*\\d{0,2}\\s*판|제?\\s*\\d{1,2}\\s*판|\\d{1,2}(?:st|nd|rd|th)\\s+[Ee]dition)\\s*[)\\]]?")
                .matcher(title);
        if (ed.find()) {
            edition = ed.group(1).replaceAll("\\s+", " ").strip();
            title = (title.substring(0, ed.start()) + title.substring(ed.end())).strip();
        }
        return new TextbookExtractor.BookClue(role, title, blankToNull(author, 200), blankToNull(publisher, 200), isbn,
                edition, unitNo, quote.length() > 300 ? quote.substring(0, 300) : quote);
    }

    static String roleOf(String word) {
        String w = word.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (w.startsWith("주교재") || w.equals("교과서") || w.startsWith("main") || w.startsWith("required")
                || w.equals("textbook") || w.equals("교재")) {
            return "MAIN";
        }
        if (w.startsWith("부교재") || w.startsWith("보조")) {
            return "SUPPLEMENT";
        }
        if (w.startsWith("참고") || w.startsWith("reference")) {
            return "REFERENCE";
        }
        return "UNKNOWN";
    }

    private static boolean sameTitle(String a, String b) {
        return a != null && b != null
                && a.replaceAll("\\s+", "").equalsIgnoreCase(b.replaceAll("\\s+", ""));
    }

    private static String blankToNull(String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
