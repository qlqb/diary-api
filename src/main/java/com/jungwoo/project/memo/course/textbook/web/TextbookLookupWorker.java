package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.MaterialTextbookExtract;
import com.jungwoo.project.memo.course.textbook.MaterialTextbookExtractMapper;
import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookExtracts;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.analysis.AnalysisFailure;
import com.jungwoo.project.memo.material.analysis.AnalysisFailureClassifier;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 교재 조회 한 건을 처리한다: 단서 확보 → 주소 후보(ISBN 캐시·사용자 링크·웹 검색) → 안전 수집 → 파싱·목차 구조화 →
 * 일치 판정·판본 묶기 → 저장(basis가 그대로일 때만).
 *
 * <p>DB 트랜잭션 안에서 외부 호출을 하지 않는다 — 상태 저장은 {@link TextbookLookupService}의 짧은 트랜잭션들이 한다.
 * 외부 호출 전에는 basis가 아직 지금 것인지 먼저 본다(이미 사용자가 교재를 고쳤다면 검색하지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextbookLookupWorker {

    public enum Result { COMPLETED, RESCHEDULED, FAILED, SUPERSEDED, AUTH_FAILURE }

    private final TextbookLookupService lookupService;
    private final BookWebSearchClient searchClient;
    private final SafePageFetcher fetcher;
    private final WebEvidenceStore store;
    private final TextbookModelAssist modelAssist;
    private final TextbookExtracts extracts;
    private final MaterialTextbookExtractMapper extractMapper;
    private final CourseMaterialMapper materialMapper;

    @Value("${textbook.lookup.page-cache-days:7}")
    private int pageCacheDays = 7;

    @Value("${textbook.lookup.job-deadline-seconds:60}")
    private int jobDeadlineSeconds = 60;

    @Value("${textbook.lookup.max-pages:6}")
    private int maxPages = 6;

    @Value("${textbook.lookup.worker.lease-seconds:180}")
    private int leaseSeconds = 180;

    /** 하루 한도로 미루는 최대 횟수. 넘으면 실패로 끝낸다(무한히 대기열에 남지 않게). */
    static final int MAX_DAILY_LIMIT_WAITS = 6;

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    public Result run(TextbookLookup claimed) {
        TextbookLookup job = claimed;
        try {
            if (!lookupService.isCurrent(job)) {
                lookupService.supersede(job);
                return Result.SUPERSEDED;
            }
            LookupResult.Query query = lookupService.readQuery(job.getQueryJson());
            if (query == null) {
                query = new LookupResult.Query(null, null, null, null, null, null, null);
            }
            if (Boolean.TRUE.equals(query.needsClue())) {
                if (!modelAssist.isConfigured()) {
                    lookupService.finishIfCurrent(job, TextbookLookup.FAILED, null, null, "NOT_CONFIGURED",
                            "서버에 모델이 설정되지 않았다", null);
                    return Result.FAILED;
                }
                keepLease(job);
                ClueRead read = readClue(job);
                // 단서 저장과 근거 갱신·종료를 한 트랜잭션에서 한다(그 사이 다른 조회가 이 작업을 지난 것으로 닫지 않게).
                TextbookLookupService.ClueOutcome next = lookupService.afterClue(job, read.extractId(), read.clueJson());
                switch (next.next()) {
                    case NO_BOOK, CONFLICT:
                        return Result.COMPLETED;
                    case STALE:
                        return Result.SUPERSEDED;
                    default:
                        break;
                }
                job = next.lookup();
                query = lookupService.readQuery(job.getQueryJson());
            }
            return search(job, query);
        } catch (LeaseLost e) {
            log.info("교재 조회: 임대를 잃어 멈춘다: lookupId={}", job.getLookupId());
            return Result.SUPERSEDED;
        } catch (DailyLimit e) {
            return retryOrFail(job, "DAILY_LIMIT", e.getMessage(), true);
        } catch (BookWebSearchClient.SearchFailure e) {
            if (e.isAuth()) {
                lookupService.finishIfCurrent(job, TextbookLookup.FAILED, null, null, "AUTH", e.getMessage(), null);
                return Result.AUTH_FAILURE;
            }
            return retryOrFail(job, e.isRateLimited() ? "RATE_LIMIT" : "SEARCH_FAILED", e.getMessage(), e.isRateLimited());
        } catch (AnalysisFailure e) {
            if (e.kind() == AnalysisFailureClassifier.Kind.AUTH) {
                lookupService.finishIfCurrent(job, TextbookLookup.FAILED, null, null, "AUTH", e.getMessage(), null);
                return Result.AUTH_FAILURE;
            }
            return retryOrFail(job, e.kind().name(), e.getMessage(), e.kind() == AnalysisFailureClassifier.Kind.RATE_LIMIT);
        } catch (Exception e) {
            log.warn("교재 조회 처리 중 예외: lookupId={}, {}", job.getLookupId(), e.getClass().getSimpleName(), e);
            return retryOrFail(job, "ERROR", e.getClass().getSimpleName(), false);
        }
    }

    // ===== 단서 =====

    /** 모델이 짚은 단서(아직 저장 전). extractId가 null이면 저장할 자리가 없다(파일이 바뀌었다). */
    record ClueRead(Long extractId, String clueJson) {
    }

    /** 규칙이 못 읽은 교재 표를 모델이 짚게 한다. 저장은 호출부가 근거 갱신과 한 트랜잭션에서 한다. */
    private ClueRead readClue(TextbookLookup job) {
        if (!lookupService.reserveCalls(job.getUserId(), 1)) {
            throw new DailyLimit();
        }
        CourseMaterial material = materialMapper.findByIdsAndUserIdIncludingDeleted(List.of(job.getClueMaterialId()),
                job.getUserId()).stream().findFirst().orElse(null);
        MaterialTextbookExtract extract = extractMapper.find(job.getClueMaterialId(), job.getClueFileHash(),
                TextbookExtractor.VERSION, job.getUserId());
        if (material == null || extract == null || !job.getClueFileHash().equals(material.getFileHash())) {
            return new ClueRead(null, null);
        }
        List<TextbookExtractor.Unit> units = extracts.units(material);
        List<String> window = ClueWindow.around(units);
        if (window.isEmpty()) {
            return new ClueRead(extract.getExtractId(),
                    extracts.write(new TextbookExtracts.ClueJson(TextbookExtracts.ClueJson.NONE, List.of())));
        }
        List<String> masked = TextbookModelAssist.maskLines(window);
        List<TextbookExtractor.BookClue> clues = modelAssist.readClues(job.getUserId(), masked, ClueWindow.unitOf(units));
        return new ClueRead(extract.getExtractId(), extracts.write(new TextbookExtracts.ClueJson(
                clues.isEmpty() ? TextbookExtracts.ClueJson.NONE : TextbookExtracts.ClueJson.MODEL, clues)));
    }

    /** 하루 외부 호출 한도. 다음 날 이어 간다(실패가 아니다). */
    static final class DailyLimit extends RuntimeException {
        DailyLimit() {
            super("오늘 교재 조회 한도");
        }
    }

    /** 임대를 잃었다 — 이 worker는 더 외부 호출을 하지 않는다. */
    static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super("임대를 잃었다");
        }
    }

    /** 외부 호출(모델·검색·페이지) 직전마다 임대를 늘린다. 못 늘리면 멈춘다(다른 worker가 같은 비용을 다시 쓰지 않게). */
    private void keepLease(TextbookLookup job) {
        if (!lookupService.renewLease(job, leaseSeconds)) {
            throw new LeaseLost();
        }
    }

    // ===== 검색·수집·판정 =====

    private Result search(TextbookLookup job, LookupResult.Query query) {
        long deadline = System.currentTimeMillis() + jobDeadlineSeconds * 1000L;
        BookMatcher.Clue clue = new BookMatcher.Clue(query.title(), query.author(), query.publisher(), query.isbn(),
                query.edition());
        boolean userLink = "USER_LINK".equals(job.getClueOrigin());
        LocalDateTime notBefore = job.isForceRefresh() ? LocalDateTime.now().plusYears(1)
                : LocalDateTime.now().minusDays(pageCacheDays);

        Map<Long, TextbookWebRevision> revisions = new LinkedHashMap<>();
        List<LookupResult.Failure> failures = new ArrayList<>();
        String searchedWith = null;
        // 조회 한 번이 여는 페이지 수와 이미 본 주소는 모든 수집 단계가 함께 쓴다.
        Budget budget = new Budget(maxPages);
        String searchNote = null;

        if (userLink) {
            searchedWith = "link";
            if (!lookupService.isCurrent(job)) {
                lookupService.supersede(job);
                return Result.SUPERSEDED;
            }
            // 사용자가 준 링크: 공개 주소면 어느 사이트든(사용자 범위 캐시). 주소 검사는 수집기가 한다.
            collect(job, List.of(query.link()), notBefore, deadline, revisions, failures, false, budget);
        }
        java.util.Set<Long> linkRevisionIds = new java.util.HashSet<>(revisions.keySet());
        // 링크 페이지가 책은 확인해 줬는데 목차를 싣지 않았다(알라딘 상세 등) — 그 ISBN으로 다른 서점의 목차를 이어서 찾는다.
        String linkIsbn = userLink ? linkedIsbnWithoutToc(clue, revisions) : null;
        // 검색에는 링크 책의 ISBN을 보탠다. 판정은 따로 한다 — 이어서 찾은 페이지는 링크 책과 ISBN이 같을 때만 같은 판이고,
        // 그 판정은 링크 페이지의 판정을 따른다(링크 책이 지금 교재로 확인되지 않았으면 사용자가 고르게 둔다).
        BookMatcher.Clue searchClue = linkIsbn == null ? clue
                : new BookMatcher.Clue(clue.title(), clue.author(), clue.publisher(), linkIsbn, clue.edition());
        String linkVerdict = linkIsbn == null ? null : linkVerdictOf(clue, revisions, linkIsbn);
        if (!userLink || linkIsbn != null) {
            String isbn13 = linkIsbn != null ? linkIsbn : BookPageParser.toIsbn13(BookPageParser.isbnOrNull(query.isbn()));
            List<String> urls = new ArrayList<>();
            searchedWith = linkIsbn != null ? "link" : "web_search";
            if (isbn13 != null) {
                searchedWith = linkIsbn != null ? "link+isbn" : "isbn";
                for (TextbookWebRevision r : job.isForceRefresh() ? List.<TextbookWebRevision>of()
                        : store.byIsbn(isbn13, job.getUserId(), LocalDateTime.now().minusDays(pageCacheDays))) {
                    revisions.put(r.getRevisionId(), r);
                }
                if (revisions.values().stream().noneMatch(r -> r.getTocEntryCount() > 0) && linkIsbn == null) {
                    urls.add("https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=" + isbn13);
                }
            }
            // 쓸 수 있는 목차만 센다 — 결국 다른 책으로 빠질 캐시 목차 때문에 검색을 건너뛰지 않게.
            boolean haveToc = hasUsableToc(clue, linkIsbn, revisions);
            if (!haveToc) {
                collect(job, urls, notBefore, deadline, revisions, failures, true, budget);
                haveToc = hasUsableToc(clue, linkIsbn, revisions);
            }
            // 링크로 이미 책을 확인했으면 이어서 하는 검색은 덤이다 — 못 해도 확인한 책 정보는 그대로 남긴다.
            boolean followUp = linkIsbn != null;
            if (!haveToc && followUp && !searchClient.isConfigured()) {
                searchNote = "다른 서점에서 목차를 찾지 못했어요(서버에 웹 검색이 설정되지 않음).";
            } else if (!haveToc && followUp && deadline - System.currentTimeMillis() < FOLLOW_UP_MIN_MILLIS) {
                searchNote = "시간이 모자라 다른 서점에서 목차를 찾지 않았어요. [다시 찾기]로 이어서 찾을 수 있어요.";
            } else if (!haveToc && budget.pagesLeft <= 0) {
                // 검색해도 열 페이지가 없다 — 검색 비용을 쓰지 않는다.
                searchNote = "이번 조회에서 열 수 있는 페이지를 다 써서 웹 검색을 하지 않았어요.";
            } else if (!haveToc) {
                if (!searchClient.isConfigured()) {
                    return finish(job, TextbookLookup.FAILED, new LookupResult(query, null, List.of(), List.of(),
                            failures, List.of(), "서버에 웹 검색이 설정되지 않았어요."), query, "NOT_CONFIGURED", null);
                }
                if (!lookupService.reserveCalls(job.getUserId(), 1)) {
                    if (!followUp) {
                        throw new DailyLimit();
                    }
                    searchNote = "오늘 웹 검색 횟수를 다 써서 다른 서점에서 목차를 찾지 않았어요.";
                } else {
                    if (!lookupService.isCurrent(job)) {
                        lookupService.supersede(job);
                        return Result.SUPERSEDED;
                    }
                    keepLease(job);
                    BookWebSearchClient.SearchResult found = null;
                    try {
                        found = searchClient.search(searchClue);
                    } catch (RuntimeException e) {
                        // 인증 실패는 이어서 하는 검색이어도 올린다 — 스케줄러가 잘못된 키로 계속 부르지 않게 멈춘다.
                        if (!followUp || e instanceof BookWebSearchClient.SearchFailure f && f.isAuth()) {
                            throw e;
                        }
                        searchNote = "다른 서점에서 목차를 찾는 검색이 실패했어요. [다시 찾기]로 다시 해 볼 수 있어요.";
                    }
                    if (found != null) {
                        searchedWith = followUp ? "link+isbn+web_search" : isbn13 != null ? "isbn+web_search" : "web_search";
                        List<String> hinted = new ArrayList<>();
                        for (BookWebSearchClient.PageHint hint : found.pages()) {
                            hinted.add(hint.url());
                        }
                        collect(job, hinted, notBefore, deadline, revisions, failures, true, budget);
                    }
                }
            }
        }

        // 일치 판정
        List<LookupResult.Candidate> candidates = new ArrayList<>();
        for (TextbookWebRevision r : revisions.values()) {
            BookMatcher.Result m = BookMatcher.match(clue, WebEvidenceStore.asParsed(r));
            List<String> reasons = m.reasons();
            String verdict;
            if (!userLink) {
                verdict = m.verdict().name();
            } else if (linkRevisionIds.contains(r.getRevisionId())) {
                // 사용자가 직접 준 링크의 페이지 — 맞다고 확인되지 않아도 사용자가 고를 수 있다.
                verdict = m.verdict() == BookMatcher.Verdict.MATCH ? "MATCH" : "LINK";
            } else if (linkIsbn != null && linkIsbn.equals(r.getIsbn13()) && m.verdict() != BookMatcher.Verdict.MISMATCH) {
                verdict = linkVerdict;
                reasons = new ArrayList<>(reasons);
                reasons.add("준 링크의 책과 ISBN이 같아요");
            } else {
                // 이어서 찾은 페이지가 링크의 책이 아니다 — 고를 수 없다.
                verdict = "MISMATCH";
                reasons = new ArrayList<>(reasons);
                reasons.add("준 링크의 책과 ISBN이 달라요");
            }
            candidates.add(new LookupResult.Candidate(r.getRevisionId(), r.getSite(), displayUrl(r), verdict, reasons,
                    r.getIsbn13(), r.getTitle(), r.getAuthors(), r.getPublisher(), r.getPublishedDate(), r.getEdition(),
                    r.getTocCoverage(), r.getTocEntryCount(), r.getFetchedAt() == null ? null : r.getFetchedAt().format(AT)));
        }
        List<LookupResult.Edition> editions = editions(candidates, revisions);
        LookupResult.Query sent = query;
        String status;
        String note = null;
        if (editions.isEmpty()) {
            // 열지 않은 링크와 "열었지만 책 페이지가 아님"은 접속 실패가 아니다(못 찾음이다).
            long fetchFailures = failures.stream().filter(f -> !"NOT_OPENED_AUTOMATICALLY".equals(f.status())
                    && !"NO_IDENTITY".equals(f.status())).count();
            boolean onlySkipped = revisions.isEmpty() && fetchFailures == 0
                    && failures.stream().anyMatch(f -> "NOT_OPENED_AUTOMATICALLY".equals(f.status()));
            boolean allFailed = revisions.isEmpty() && fetchFailures > 0;
            status = allFailed ? TextbookLookup.ACCESS_FAILED : TextbookLookup.NOT_FOUND;
            note = allFailed ? (userLink
                    ? "그 페이지를 열지 못했어요(사이트가 자동 접속을 막았을 수 있어요). 예스24 같은 다른 서점의 상세 페이지 링크를 주세요."
                    : "찾은 페이지에 접속하지 못했어요.") : onlySkipped
                    ? "자동으로 열지 않는 사이트(출판사·블로그 등)의 페이지만 찾았어요. 그 상세 페이지 링크를 주면 열어 볼게요."
                    : candidates.isEmpty()
                    ? "이 단서로 맞는 책 페이지를 찾지 못했어요. 책이 없다는 뜻은 아니에요."
                    : "찾은 페이지가 모두 다른 책·다른 권이거나 정보가 모자라 확인하지 못했어요.";
        } else if (editions.size() == 1 && !(userLink && "LINK".equals(firstVerdict(candidates, editions.get(0))))) {
            status = editions.get(0).tocEntryCount() > 0 ? TextbookLookup.FOUND : TextbookLookup.BOOK_NO_TOC;
        } else {
            status = TextbookLookup.NEEDS_CHOICE;
        }
        if (searchNote != null && !TextbookLookup.FOUND.equals(status) && note == null) {
            note = searchNote;
        }
        String autoTidy = TextbookLookup.FOUND.equals(status) ? "PENDING" : "NONE";
        LookupResult result = new LookupResult(sent, searchedWith, candidates, editions, failures, List.of(), note);
        return finish(job, status, result, sent, null, autoTidy);
    }

    /** 이어서 하는 검색을 시작할 최소 남은 시간 — 검색 뒤 페이지를 받을 시간까지. */
    private static final long FOLLOW_UP_MIN_MILLIS = 25_000;

    /** 조회 한 번의 페이지 예산과 이미 본 주소. */
    private static final class Budget {
        int pagesLeft;
        final java.util.Set<String> visited = new java.util.HashSet<>();

        Budget(int pages) {
            this.pagesLeft = pages;
        }
    }

    /** 최종 후보로 남을 목차가 있다 — 다른 책(MISMATCH)이 아니고, 링크를 이어 찾는 중이면 링크 책과 ISBN이 같다. */
    private static boolean hasUsableToc(BookMatcher.Clue clue, String linkIsbn, Map<Long, TextbookWebRevision> revisions) {
        for (TextbookWebRevision r : revisions.values()) {
            if (r.getTocEntryCount() <= 0 || linkIsbn != null && !linkIsbn.equals(r.getIsbn13())) {
                continue;
            }
            if (BookMatcher.match(clue, WebEvidenceStore.asParsed(r)).verdict() != BookMatcher.Verdict.MISMATCH) {
                return true;
            }
        }
        return false;
    }

    /** 링크 페이지(그 ISBN)의 지금 교재 단서 판정 — 맞으면 MATCH, 확인 못 했으면 LINK(사용자가 고른다). */
    private static String linkVerdictOf(BookMatcher.Clue clue, Map<Long, TextbookWebRevision> revisions, String isbn) {
        for (TextbookWebRevision r : revisions.values()) {
            if (isbn.equals(r.getIsbn13())
                    && BookMatcher.match(clue, WebEvidenceStore.asParsed(r)).verdict() == BookMatcher.Verdict.MATCH) {
                return "MATCH";
            }
        }
        return "LINK";
    }

    /**
     * 사용자 링크에서 읽은 책의 ISBN — 그 페이지가 다른 책이라고 판정되지 않았고 목차가 없을 때만. 목차가 있으면 null.
     */
    private static String linkedIsbnWithoutToc(BookMatcher.Clue clue, Map<Long, TextbookWebRevision> revisions) {
        if (revisions.values().stream().anyMatch(r -> r.getTocEntryCount() > 0)) {
            return null;
        }
        for (TextbookWebRevision r : revisions.values()) {
            if (r.getIsbn13() != null
                    && BookMatcher.match(clue, WebEvidenceStore.asParsed(r)).verdict() != BookMatcher.Verdict.MISMATCH) {
                return r.getIsbn13();
            }
        }
        return null;
    }

    /**
     * @param automatic 웹 검색·ISBN 조회가 낸 주소다 — 지원 서점의 상품 페이지만 열고(리다이렉트 포함), 그 밖의 주소는 열지 않고
     *                  "자동으로 열지 않음"으로 남긴다(사용자가 그 링크를 직접 주면 그때 연다)
     */
    private void collect(TextbookLookup job, List<String> urls, LocalDateTime notBefore, long deadline,
                         Map<Long, TextbookWebRevision> revisions, List<LookupResult.Failure> failures, boolean automatic,
                         Budget budget) {
        for (String raw : urls) {
            if (raw == null || budget.pagesLeft <= 0 || System.currentTimeMillis() >= deadline) {
                break;
            }
            WebEvidenceStore.Target target;
            try {
                target = store.target(raw, job.getUserId());
            } catch (IllegalArgumentException e) {
                failures.add(new LookupResult.Failure(SafePageFetcher.masked(raw), "INVALID_URL"));
                continue;
            }
            if (automatic && !"SHARED".equals(target.cacheScopeKey())) {
                failures.add(new LookupResult.Failure(SafePageFetcher.masked(target.url()), "NOT_OPENED_AUTOMATICALLY"));
                continue;
            }
            if (!budget.visited.add(target.url())) {
                continue; // 이 조회에서 이미 본 주소(사용자 링크가 검색 결과에 다시 나오는 경우 등)
            }
            budget.pagesLeft--;
            TextbookWebRevision cached = store.cached(target, job.getUserId(), notBefore);
            if (cached != null) {
                if (WebEvidenceStore.asParsed(cached).hasIdentity()) {
                    revisions.putIfAbsent(cached.getRevisionId(), cached);
                } else {
                    failures.add(new LookupResult.Failure(SafePageFetcher.masked(target.url()), "NO_IDENTITY"));
                }
                continue;
            }
            keepLease(job);
            SafePageFetcher.Page page = automatic ? fetcher.fetch(target.url(), WebEvidenceStore::supportedHost)
                    : fetcher.fetch(target.url());
            if (page.status() != SafePageFetcher.Status.OK) {
                store.recordFailure(target, page.status().name());
                failures.add(new LookupResult.Failure(SafePageFetcher.masked(target.url()), page.status().name()));
                continue;
            }
            BookPageParser.Parsed parsed = BookPageParser.parse(target.url(), page.html());
            if (!parsed.hasIdentity()) {
                store.recordFailure(target, "NO_IDENTITY");
                failures.add(new LookupResult.Failure(SafePageFetcher.masked(target.url()), "NO_IDENTITY"));
                continue;
            }
            WebTocStructurer.Structured toc = WebTocStructurer.byRules(parsed.tocRaw(), parsed.tocTruncated());
            // 모델 보조 구조화도 작업 기한 안에서만(기한이 지났으면 규칙 결과로 남기고 범위를 확인 못 함으로 둔다).
            long remaining = (deadline - System.currentTimeMillis()) / 1000;
            if (toc.entries().isEmpty() && WebTocStructurer.lines(parsed.tocRaw()).size() >= 2
                    && remaining >= 5
                    && modelAssist.isConfigured() && lookupService.reserveCalls(job.getUserId(), 1)) {
                keepLease(job);
                List<WebTocStructurer.Pick> picks = modelAssist.pickTocLines(job.getUserId(),
                        WebTocStructurer.lines(parsed.tocRaw()), (int) remaining);
                toc = WebTocStructurer.fromModelPicks(parsed.tocRaw(), picks, parsed.tocTruncated());
            }
            TextbookWebRevision saved = store.save(target, parsed, toc, page.httpStatus(), LocalDateTime.now(),
                    job.getUserId());
            revisions.putIfAbsent(saved.getRevisionId(), saved);
        }
    }

    /** MATCH(사용자 링크는 LINK 포함) 후보를 판본(ISBN)마다 묶는다. 판 안에서는 목차를 가장 온전히 읽은 리비전이 대표다. */
    static List<LookupResult.Edition> editions(List<LookupResult.Candidate> candidates,
                                               Map<Long, TextbookWebRevision> revisions) {
        Map<String, List<LookupResult.Candidate>> groups = new LinkedHashMap<>();
        for (LookupResult.Candidate c : candidates) {
            if (!"MATCH".equals(c.verdict()) && !"LINK".equals(c.verdict())) {
                continue;
            }
            String key = c.isbn13() != null ? c.isbn13() : "url:" + c.url();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        // ISBN을 확인한 판이 있으면, ISBN 없는 페이지는 따로 판으로 세지 않는다(어느 판인지 가를 수 없는 같은 책의 페이지다).
        if (groups.keySet().stream().anyMatch(k -> !k.startsWith("url:"))) {
            groups.keySet().removeIf(k -> k.startsWith("url:"));
        }
        List<LookupResult.Edition> out = new ArrayList<>();
        for (Map.Entry<String, List<LookupResult.Candidate>> g : groups.entrySet()) {
            LookupResult.Candidate best = g.getValue().stream()
                    .max(Comparator.comparingInt((LookupResult.Candidate c) -> coverageRank(c.tocCoverage()))
                            .thenComparingInt(LookupResult.Candidate::tocEntryCount))
                    .orElseThrow();
            out.add(new LookupResult.Edition(g.getKey(), best.isbn13(), best.title(), best.authors(), best.publisher(),
                    best.publishedDate(), best.edition(), best.revisionId(), best.site(), best.url(), best.tocCoverage(),
                    best.tocEntryCount(), g.getValue().stream().map(LookupResult.Candidate::revisionId).toList(),
                    new ArrayList<>()));
        }
        // 목차가 같은 판끼리 표시(판본 선택을 돕는다 — 같다고 판을 대신 고르지는 않는다).
        for (LookupResult.Edition a : out) {
            for (LookupResult.Edition b : out) {
                if (a != b && a.tocEntryCount() > 0 && sameToc(revisions.get(a.bestRevisionId()), revisions.get(b.bestRevisionId()))) {
                    a.sameTocAs().add(b.key());
                }
            }
        }
        out.sort(Comparator.comparing((LookupResult.Edition e) -> e.publishedDate() == null ? "" : e.publishedDate())
                .reversed());
        return out;
    }

    private static boolean sameToc(TextbookWebRevision a, TextbookWebRevision b) {
        return a != null && b != null && a.getTocRaw() != null && b.getTocRaw() != null
                && a.getTocRaw().replaceAll("\\s+", "").equals(b.getTocRaw().replaceAll("\\s+", ""));
    }

    static int coverageRank(String coverage) {
        return switch (coverage == null ? "" : coverage) {
            case "PAGE_FULL" -> 3;
            case "PARTIAL" -> 2;
            case "UNKNOWN" -> 1;
            default -> 0;
        };
    }

    private static String firstVerdict(List<LookupResult.Candidate> candidates, LookupResult.Edition edition) {
        return candidates.stream().filter(c -> c.revisionId().equals(edition.bestRevisionId())).map(LookupResult.Candidate::verdict)
                .findFirst().orElse(null);
    }

    private static String displayUrl(TextbookWebRevision r) {
        return "SHARED".equals(r.getCacheScopeKey()) ? r.getUrl() : SafePageFetcher.masked(r.getUrl());
    }

    // ===== 마무리 =====

    private Result finish(TextbookLookup job, String status, LookupResult result, LookupResult.Query query,
                          String errorCode, String autoTidy) {
        boolean saved = lookupService.finishIfCurrent(job, status, result, query, errorCode, null, autoTidy);
        log.info("교재 조회 종료: lookupId={}, courseId={}, status={}, saved={}", job.getLookupId(), job.getCourseId(),
                status, saved);
        return saved ? Result.COMPLETED : Result.SUPERSEDED;
    }

    private Result retryOrFail(TextbookLookup job, String code, String message, boolean rateLimited) {
        int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
        int max = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
        if (rateLimited) {
            if (attempt >= max + MAX_DAILY_LIMIT_WAITS) {
                lookupService.finishIfCurrent(job, TextbookLookup.FAILED, null, null, code, message, null);
                return Result.FAILED;
            }
            // 하루 한도·요청 한도: 바로 실패로 끝내지 않고 미룬다(사용자가 [다시 찾기]를 누르지 않아도 이어진다).
            lookupService.reschedule(job, LocalDateTime.now().plusMinutes(Math.min(360, 30L * attempt)), code, message);
            return Result.RESCHEDULED;
        }
        if (attempt >= max) {
            lookupService.finishIfCurrent(job, TextbookLookup.FAILED, null, null, code, message, null);
            return Result.FAILED;
        }
        lookupService.reschedule(job, LocalDateTime.now().plusSeconds(attempt == 1 ? 20 : 120), code, message);
        return Result.RESCHEDULED;
    }
}
