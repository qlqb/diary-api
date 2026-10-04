package com.jungwoo.project.memo.course.textbook.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 웹 근거의 캐시 자리(페이지)와 불변 리비전을 쓰고 읽는다. 외부 호출은 하지 않는다.
 *
 * <p>공유(SHARED)는 지원 서점의 정규화된 상품 주소뿐이다. 그 밖의 주소(사용자 링크·출판사 페이지)는 그 사용자 범위
 * (USER:{id})에만 남는다 — 개인 링크의 내용이 다른 사용자에게 재사용되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class WebEvidenceStore {

    private final TextbookWebMapper webMapper;
    private final ObjectMapper objectMapper;

    /** 자동 수집(웹 검색 결과·ISBN 조회)이 열 수 있는 사이트 — 지원 서점뿐. 리다이렉트도 이 안에서만. */
    public static boolean supportedHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("yes24.com") || h.endsWith(".yes24.com") || h.equals("aladin.co.kr") || h.endsWith(".aladin.co.kr")
                || h.equals("kyobobook.co.kr") || h.endsWith(".kyobobook.co.kr");
    }

    /** 받을 주소와 그 캐시 범위. */
    public record Target(String url, String site, String cacheScopeKey) {
    }

    /** 예스24 상품 상세: 데스크톱(/product/goods/{id})과 모바일(m.yes24.com/Goods/Detail/{id}) — 같은 데스크톱 상세로 정규화한다. */
    private static final Pattern YES24 = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?yes24\\.com/(?:product/goods|Goods/Detail)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALADIN_ITEM = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?aladin\\.co\\.kr/(?:m/)?(?:shop/)?[wm]product\\.aspx\\?.*\\b(ItemId|ISBN)=([0-9A-Za-z]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KYOBO = Pattern.compile("^https?://product\\.kyobobook\\.co\\.kr/detail/(S\\d+)",
            Pattern.CASE_INSENSITIVE);

    /** 주소를 정규화하고 범위를 정한다. 받을 수 없는 주소면 IllegalArgumentException. */
    public Target target(String rawUrl, Long userId) {
        URI uri = SafePageFetcher.normalize(rawUrl);
        String url = uri.toString();
        Matcher m = YES24.matcher(url);
        if (m.find()) {
            return new Target("https://www.yes24.com/product/goods/" + m.group(1), "yes24", "SHARED");
        }
        m = ALADIN_ITEM.matcher(url);
        if (m.find()) {
            return new Target("https://www.aladin.co.kr/shop/wproduct.aspx?" + m.group(1) + "=" + m.group(2), "aladin",
                    "SHARED");
        }
        m = KYOBO.matcher(url);
        if (m.find()) {
            return new Target("https://product.kyobobook.co.kr/detail/" + m.group(1), "kyobo", "SHARED");
        }
        return new Target(url, BookPageParser.siteOf(url), "USER:" + userId);
    }

    /** maxAge 안에 받은 최신 리비전(범위 안). 없으면 null. */
    @Transactional(readOnly = true)
    public TextbookWebRevision cached(Target target, Long userId, LocalDateTime notBefore) {
        TextbookWebPage page = webMapper.findPage(target.cacheScopeKey(), TextbookLookupPlanner.hash(target.url()));
        if (page == null || page.getLatestRevisionId() == null || page.getLastFetchedAt() == null
                || page.getLastFetchedAt().isBefore(notBefore) || !"OK".equals(page.getLastFetchStatus())) {
            return null;
        }
        TextbookWebRevision revision = webMapper.findRevision(page.getLatestRevisionId(), userId);
        // 파서 규칙이 바뀌었으면(옛 판으로 읽은 리비전) 다시 받아 새 규칙으로 읽는다.
        if (revision == null || !Integer.valueOf(BookPageParser.VERSION).equals(revision.getParserVersion())) {
            return null;
        }
        return revision;
    }

    @Transactional(readOnly = true)
    public List<TextbookWebRevision> byIsbn(String isbn13, Long userId, LocalDateTime notBefore) {
        return webMapper.findLatestByIsbn(isbn13, userId, notBefore, BookPageParser.VERSION);
    }

    /** 받은 페이지를 리비전으로 남긴다. 같은 내용·같은 파서면 기존 리비전을 돌려준다. */
    @Transactional
    public TextbookWebRevision save(Target target, BookPageParser.Parsed parsed, WebTocStructurer.Structured toc,
                                    int httpStatus, LocalDateTime fetchedAt, Long userId) {
        TextbookWebPage page = page(target);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", parsed.title());
        content.put("authors", parsed.authors());
        content.put("authorNotes", parsed.authorNotes());
        content.put("publisher", parsed.publisher());
        content.put("isbn13", parsed.isbn13());
        content.put("published", parsed.publishedDate());
        content.put("edition", parsed.edition());
        content.put("tocRaw", parsed.tocRaw());
        content.put("tocTruncated", parsed.tocTruncated());
        // 구조화 결과도 근거의 일부다 — 같은 원문이라도 다르게 읽었으면(규칙/모델 보조, 첫 수집 때 한도로 못 읽음) 새 리비전이다.
        content.put("tocMethod", toc.method());
        content.put("tocEntries", toc.entries());
        String hash = TextbookLookupPlanner.hash(write(content));
        TextbookWebRevision revision = TextbookWebRevision.builder()
                .pageId(page.getPageId()).contentHash(hash).parserVersion(BookPageParser.VERSION)
                .fetchedAt(fetchedAt).httpStatus(httpStatus).isbn13(parsed.isbn13())
                .title(parsed.title()).authors(joinAuthors(parsed)).authorNotes(joinNotes(parsed)).publisher(parsed.publisher())
                .publishedDate(parsed.publishedDate()).edition(parsed.edition()).tocRaw(parsed.tocRaw())
                .tocJson(toc.entries().isEmpty() ? null : write(Map.of("entries", toc.entries(), "method", toc.method(),
                        "lines", toc.lines(), "readLines", toc.readLines())))
                .tocEntryCount(toc.entries().size()).tocCoverage(toc.coverage())
                .build();
        webMapper.insertRevisionIgnore(revision);
        TextbookWebRevision saved = webMapper.findRevisionByContent(page.getPageId(), hash, BookPageParser.VERSION);
        webMapper.updatePageFetch(page.getPageId(), saved.getRevisionId(), "OK", fetchedAt);
        return webMapper.findRevision(saved.getRevisionId(), userId);
    }

    @Transactional
    public void recordFailure(Target target, String status) {
        TextbookWebPage page = page(target);
        webMapper.updatePageFetch(page.getPageId(), null, status, LocalDateTime.now());
    }

    private TextbookWebPage page(Target target) {
        String urlHash = TextbookLookupPlanner.hash(target.url());
        TextbookWebPage page = webMapper.findPage(target.cacheScopeKey(), urlHash);
        if (page == null) {
            webMapper.insertPageIgnore(TextbookWebPage.builder().cacheScopeKey(target.cacheScopeKey()).urlHash(urlHash)
                    .url(target.url().length() > 1000 ? target.url().substring(0, 1000) : target.url())
                    .site(target.site()).build());
            page = webMapper.findPage(target.cacheScopeKey(), urlHash);
        }
        return page;
    }

    private static String joinAuthors(BookPageParser.Parsed parsed) {
        if (parsed.authors().isEmpty()) {
            return null;
        }
        String joined = String.join(", ", parsed.authors());
        return joined.length() > 300 ? joined.substring(0, 300) : joined;
    }

    private static String joinNotes(BookPageParser.Parsed parsed) {
        if (parsed.authorNotes().isEmpty()) {
            return null;
        }
        String joined = String.join(", ", parsed.authorNotes());
        return joined.length() > 300 ? joined.substring(0, 300) : joined;
    }

    /** 저장된 리비전을 일치 판정에 다시 쓸 수 있는 모양으로. */
    public static BookPageParser.Parsed asParsed(TextbookWebRevision r) {
        return new BookPageParser.Parsed(r.getSite(), r.getTitle(), split(r.getAuthors()), split(r.getAuthorNotes()),
                r.getPublisher(), r.getIsbn13(), r.getPublishedDate(), r.getEdition(), r.getTocRaw(),
                "PARTIAL".equals(r.getTocCoverage()));
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split("\s*,\s*"));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
