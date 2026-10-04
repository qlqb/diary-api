package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 도서 상세 페이지 HTML에서 "어느 책인가"와 "목차 원문"을 읽는다. 모델을 부르지 않는다.
 *
 * <ul>
 *   <li>사이트별 어댑터가 있는 곳(yes24)은 페이지의 정해진 칸에서 읽는다. 목차는 페이지가 실은 원문 덩어리 그대로 —
 *       구조화는 {@link WebTocStructurer}가 한다.</li>
 *   <li>그 밖의 페이지는 og:title·체크섬 맞는 ISBN·"목차/차례/Contents" 머리 아래 덩어리만 본다. 확신이 없으면 비운다
 *       (없는 저자·판을 추측해 채우지 않는다).</li>
 *   <li>페이지 글은 데이터다. 여기서 읽은 문자열은 길이를 자르고 제어 문자를 지운 뒤에만 밖으로 나간다.</li>
 * </ul>
 * 규칙이 바뀌면 {@link #VERSION}을 올린다 — 같은 원문이라도 새 리비전으로 다시 읽는다.
 */
public final class BookPageParser {

    public static final int VERSION = 2;

    static final int MAX_TOC_RAW_CHARS = 20_000;

    private BookPageParser() {
    }

    /**
     * @param authorNotes 저자 소개 등에서 읽은 다른 표기(예: 한글 음역 옆의 로마자 이름). 일치 판정의 보조 근거다
     * @param tocTruncated 페이지가 목차를 줄여 실었다는 표시가 있다("더보기" 원문이 없고 접힌 일부만)
     */
    public record Parsed(String site, String title, List<String> authors, List<String> authorNotes, String publisher,
                         String isbn13, String publishedDate, String edition, String tocRaw, boolean tocTruncated) {

        /**
         * 책 페이지로 볼 근거가 있다 — 체크섬 맞는 ISBN이 있거나, 제목과 함께 저자·출판사·목차 중 하나가 있다.
         * 제목(페이지 제목)만 있는 일반 페이지는 책 페이지가 아니다("못 찾음"이지 고를 판이 아니다).
         */
        public boolean hasIdentity() {
            return isbn13 != null || title != null
                    && (authors != null && !authors.isEmpty() || publisher != null || tocRaw != null && !tocRaw.isBlank());
        }
    }

    public static String siteOf(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return "other";
        }
        if (host == null) {
            return "other";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("yes24.com") || host.endsWith(".yes24.com")) {
            return "yes24";
        }
        if (host.equals("aladin.co.kr") || host.endsWith(".aladin.co.kr")) {
            return "aladin";
        }
        if (host.equals("kyobobook.co.kr") || host.endsWith(".kyobobook.co.kr")) {
            return "kyobo";
        }
        return "other";
    }

    public static Parsed parse(String url, String html) {
        Document doc = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        String site = siteOf(url);
        return switch (site) {
            case "yes24" -> yes24(doc);
            default -> generic(site, doc);
        };
    }

    // ===== yes24 =====

    private static Parsed yes24(Document doc) {
        String title = clean(text(doc.selectFirst("h2.gd_name")), 300);
        String subtitle = clean(text(doc.selectFirst("h3.gd_nameE")), 200);
        List<String> authors = new ArrayList<>();
        for (Element a : doc.select("span.gd_auth a")) {
            String name = clean(a.text(), 100);
            if (name != null && !authors.contains(name)) {
                authors.add(name);
            }
        }
        String publisher = clean(text(doc.selectFirst("span.gd_pub")), 200);
        String date = normalizeDate(text(doc.selectFirst("span.gd_date")));
        String isbn = null;
        for (Element th : doc.select("th")) {
            String head = th.text().trim();
            Element td = th.nextElementSibling();
            if (td == null) {
                continue;
            }
            if (head.equalsIgnoreCase("ISBN13")) {
                isbn = isbnOrNull(td.text());
            } else if (head.equalsIgnoreCase("ISBN10") && isbn == null) {
                String ten = isbnOrNull(td.text());
                isbn = ten == null ? null : toIsbn13(ten);
            } else if (date == null && head.contains("발행일")) {
                date = normalizeDate(td.text());
            }
        }
        // 저자 소개의 다른 표기: <span class="name_other">(Michael A. Putlack)</span>
        List<String> notes = new ArrayList<>();
        for (Element other : doc.select("span.name_other")) {
            String v = clean(other.text().replaceAll("[()]", " "), 100);
            if (v != null && notes.size() < 10 && !notes.contains(v)) {
                notes.add(v);
            }
        }
        String tocRaw = null;
        boolean truncated = false;
        Element toc = doc.getElementById("infoset_toc");
        if (toc != null) {
            Element full = toc.selectFirst("textarea.txtContentText");
            if (full != null) {
                // textarea 안의 HTML 원문(<br/>로 줄을 나눈다)이 접히지 않은 전체다.
                tocRaw = htmlToLines(full.wholeText());
            } else {
                Element body = toc.selectFirst(".infoWrap_txt");
                tocRaw = body == null ? null : htmlToLines(body.html());
                truncated = toc.hasClass("gd_infoSetCrop");
            }
        }
        String edition = editionOf(title, subtitle);
        return new Parsed("yes24", title, authors, notes, publisher, isbn, date, edition, cutRaw(tocRaw), truncated);
    }

    // ===== 일반 =====

    private static final Pattern TOC_HEAD = Pattern.compile("^\\s*(목\\s*차|차\\s*례|contents|table of contents)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static Parsed generic(String site, Document doc) {
        String title = meta(doc, "og:title");
        if (title == null) {
            title = clean(doc.title(), 300);
        }
        // "제목 | 저자 | 출판사 - 서점" 꼴이면 첫 칸만 제목으로 본다.
        if (title != null) {
            title = clean(title.split("\\s[|\\-–]\\s")[0], 300);
        }
        // 서지 메타(books:isbn·author·JSON-LD Book)가 있으면 먼저 쓴다 — 알라딘 상세가 이렇게 싣는다.
        String metaIsbn = toIsbn13(isbnOrNull(meta(doc, "books:isbn")));
        JsonLdBook ld = jsonLdBook(doc, title, metaIsbn);
        if (ld != null && ld.name() != null && title == null) {
            title = ld.name();
        }
        String isbn = metaIsbn;
        if (isbn == null && ld != null) {
            isbn = toIsbn13(isbnOrNull(ld.isbn()));
        }
        String bodyText = doc.body() == null ? "" : doc.body().text();
        // 13자리 ISBN 그대로("9788947288132")나 하이픈·공백이 섞인 것("978-89-472-8813-2").
        Matcher m = Pattern.compile("(97[89][\\d\\-\\s]{9,16}\\d)").matcher(bodyText);
        while (isbn == null && m.find()) {
            isbn = toIsbn13(isbnOrNull(m.group(1)));
        }
        List<String> authors = new ArrayList<>();
        if (ld != null && !ld.authors().isEmpty()) {
            authors.addAll(ld.authors());
        } else if (isbn != null || String.valueOf(meta(doc, "og:type")).toLowerCase(Locale.ROOT).startsWith("book")) {
            // 작성자 메타는 책이라는 근거(ISBN·책 종류 표시)가 있을 때만 책 저자로 본다 — 일반 글의 작성자는 저자가 아니다.
            String metaAuthor = meta(doc, "og:author");
            if (metaAuthor == null) {
                Element el = doc.selectFirst("meta[name=author]");
                metaAuthor = el == null ? null : clean(el.attr("content"), 200);
            }
            if (metaAuthor != null) {
                authors.add(metaAuthor);
            }
        }
        String publisher = ld == null ? null : ld.publisher();
        String published = ld == null ? null : ld.datePublished();
        String tocRaw = null;
        if (doc.body() != null) {
            for (Element el : doc.body().select("h1,h2,h3,h4,h5,dt,strong,th")) {
                if (TOC_HEAD.matcher(el.text()).matches()) {
                    Element block = el.nextElementSibling();
                    if (block == null && el.parent() != null) {
                        block = el.parent().nextElementSibling();
                    }
                    if (block != null) {
                        tocRaw = htmlToLines(block.html());
                        break;
                    }
                }
            }
        }
        return new Parsed(site, title, List.copyOf(authors), List.of(), publisher, isbn, published,
                editionOf(title, null), cutRaw(tocRaw), false);
    }

    record JsonLdBook(String name, List<String> authors, String publisher, String isbn, String datePublished) {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 이 페이지의 책인 JSON-LD Book. 추천 도서·다른 판의 Book이 섞일 수 있으니, 메타 ISBN이나 페이지 제목과 맞는
     * 노드만 고른다. Book이 하나뿐이면 그것(메타 ISBN과 다르면 버림), 여럿인데 맞는 것이 하나가 아니면 쓰지 않는다.
     * 형식이 틀리면 없는 것으로 본다(페이지 글은 데이터다).
     */
    static JsonLdBook jsonLdBook(Document doc, String pageTitle, String metaIsbn) {
        List<JsonLdBook> books = jsonLdBooks(doc);
        if (books.size() == 1) {
            String onlyIsbn = toIsbn13(isbnOrNull(books.get(0).isbn()));
            return metaIsbn != null && onlyIsbn != null && !metaIsbn.equals(onlyIsbn) ? null : books.get(0);
        }
        List<JsonLdBook> matching = new ArrayList<>();
        for (JsonLdBook b : books) {
            String bIsbn = toIsbn13(isbnOrNull(b.isbn()));
            boolean byIsbn = metaIsbn != null && metaIsbn.equals(bIsbn);
            boolean byTitle = metaIsbn == null && pageTitle != null && b.name() != null
                    && b.name().replaceAll("\\s+", "").equalsIgnoreCase(pageTitle.replaceAll("\\s+", ""));
            if (byIsbn || byTitle) {
                matching.add(b);
            }
        }
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static List<JsonLdBook> jsonLdBooks(Document doc) {
        List<JsonLdBook> out = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String raw = script.data();
            if (raw == null || raw.length() > 200_000) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(raw);
                List<com.fasterxml.jackson.databind.JsonNode> candidates = new ArrayList<>();
                if (node.isArray()) {
                    node.forEach(candidates::add);
                } else {
                    candidates.add(node);
                    node.path("@graph").forEach(candidates::add);
                }
                for (com.fasterxml.jackson.databind.JsonNode n : candidates) {
                    if (!"Book".equalsIgnoreCase(n.path("@type").asText(""))) {
                        continue;
                    }
                    List<String> authors = new ArrayList<>();
                    com.fasterxml.jackson.databind.JsonNode a = n.path("author");
                    (a.isArray() ? a : JSON.createArrayNode().add(a)).forEach(x -> {
                        String v = clean(x.isTextual() ? x.asText() : x.path("name").asText(null), 200);
                        if (v != null) {
                            authors.add(v);
                        }
                    });
                    com.fasterxml.jackson.databind.JsonNode p = n.path("publisher");
                    String publisher = clean(p.isTextual() ? p.asText() : p.path("name").asText(null), 200);
                    String date = clean(n.path("datePublished").asText(null), 20);
                    out.add(new JsonLdBook(clean(n.path("name").asText(null), 300), authors, publisher,
                            clean(n.path("isbn").asText(null), 30), date != null && date.matches("\\d{4}-\\d{2}-\\d{2}.*")
                            ? date.substring(0, 10) : null));
                }
            } catch (Exception ignored) {
                // 형식이 틀린 JSON-LD는 근거가 아니다.
            }
        }
        return out;
    }

    // ===== 도움 =====

    private static String text(Element el) {
        return el == null ? null : el.text();
    }

    private static String meta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        return el == null ? null : clean(el.attr("content"), 300);
    }

    /** HTML 조각을 줄 단위 평문으로. &lt;br&gt;·블록 요소가 줄 경계다. 태그 안 글만 남긴다. */
    static String htmlToLines(String html) {
        if (html == null) {
            return null;
        }
        String marked = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h\\d|dd|dt)>", "\n");
        String text = Jsoup.parse(marked).wholeText();
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R")) {
            String c = clean(line, 300);
            if (c != null) {
                out.append(c).append('\n');
            }
        }
        return out.isEmpty() ? null : out.toString().strip();
    }

    /** 제어 문자·연속 공백을 지우고 자른다. 비면 null. */
    static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace(' ', ' ').replaceAll("\\s+", " ").strip();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String cutRaw(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > MAX_TOC_RAW_CHARS ? raw.substring(0, MAX_TOC_RAW_CHARS) : raw;
    }

    public static String isbnOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (!TextbookExtractor.isValidIsbn(digits)) {
            return null;
        }
        return digits;
    }

    public static String toIsbn13(String isbn) {
        if (isbn == null || isbn.length() == 13) {
            return isbn;
        }
        String core = "978" + isbn.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (core.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return core + ((10 - sum % 10) % 10);
    }

    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*[년.\\-/]\\s*(\\d{1,2})\\s*[월.\\-/]\\s*(\\d{1,2})?");

    static String normalizeDate(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = DATE.matcher(raw);
        if (!m.find()) {
            return null;
        }
        String y = m.group(1);
        String mo = String.format("%02d", Integer.parseInt(m.group(2)));
        return m.group(3) == null ? y + "-" + mo : y + "-" + mo + "-" + String.format("%02d", Integer.parseInt(m.group(3)));
    }

    private static final Pattern EDITION = Pattern.compile(
            "(개정\\s*\\d{1,2}\\s*판|개정판|제\\s*\\d{1,2}\\s*판|\\d{1,2}\\s*판|\\d{1,2}(?:st|nd|rd|th)\\s+[Ee]dition)");

    /** 제목·부제에 판 표기가 있으면 그것만. 발행일이나 ISBN으로 판을 지어내지 않는다. */
    static String editionOf(String title, String subtitle) {
        Set<String> found = new LinkedHashSet<>();
        for (String s : new String[]{title, subtitle}) {
            if (s == null) {
                continue;
            }
            Matcher m = EDITION.matcher(s);
            if (m.find()) {
                found.add(m.group(1).replaceAll("\\s+", " "));
            }
        }
        return found.isEmpty() ? null : String.join(" ", found);
    }

    /** 페이지의 상품 링크 중 같은 사이트의 상세 페이지 후보(검색 결과 페이지에서 쓴다). */
    static List<String> productLinks(Document doc, Pattern productPath) {
        List<String> out = new ArrayList<>();
        Elements links = doc.select("a[href]");
        for (Element a : links) {
            String href = a.absUrl("href");
            if (productPath.matcher(href).find() && !out.contains(href)) {
                out.add(href);
            }
        }
        return out;
    }
}
