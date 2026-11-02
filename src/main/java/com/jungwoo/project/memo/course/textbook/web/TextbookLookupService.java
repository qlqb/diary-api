package com.jungwoo.project.memo.course.textbook.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.textbook.CourseTextbookWriter;
import com.jungwoo.project.memo.course.textbook.TextbookChangedEvent;
import com.jungwoo.project.memo.course.textbook.TextbookExtracts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 교재 조회 작업의 상태 전이. 외부 호출은 하지 않는다(그건 {@link TextbookLookupWorker}) — 여기는 짧은 트랜잭션만.
 *
 * <ul>
 *   <li>{@link #ensure}: 지금 찾아야 할 것(basis)과 마지막 조회가 다르면 열린 조회를 닫고 새로 등록한다. 같은 질의의 완료
 *       결과가 있으면 검색 없이 새 basis 행으로 복사한다. 과목 행을 잠가 같은 과목의 등록이 겹치지 않는다.</li>
 *   <li>{@link #finishIfCurrent}: worker의 결과는 임대 토큰이 그대로이고 basis가 지금과 같을 때만 쓴다. 다르면 SUPERSEDED —
 *       늦게 끝난 옛 교재 검색이 사용자가 고친 교재를 덮지 않는다.</li>
 *   <li>{@link #choose}: 사용자가 고른 판을 교재 칸에 쓴다(교재 쓰기 경로 하나). 그 lookup이 그 사용자·과목의 것이고 지금
 *       basis의 것이며 고른 리비전이 그 조회의 허용 후보일 때만.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextbookLookupService {

    private final CourseMapper courseMapper;
    private final TextbookLookupMapper lookupMapper;
    private final TextbookWebMapper webMapper;
    private final TextbookExtracts extracts;
    private final CourseTextbookWriter textbookWriter;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final com.jungwoo.project.memo.course.textbook.MaterialTextbookExtractMapper extractMapper;

    @Value("${textbook.lookup.reuse-days:30}")
    private int reuseDays = 30;

    @Value("${textbook.lookup.not-found-reuse-hours:24}")
    private int notFoundReuseHours = 24;

    @Value("${textbook.lookup.daily-call-limit:20}")
    private int dailyCallLimit = 20;

    // ===== 등록 =====

    /**
     * 지금 근거에 맞는 조회가 있게 한다. 반환은 지금 basis의 조회(없으면 null — 단서 없음·꺼짐).
     */
    @Transactional
    public TextbookLookup ensure(Long userId, Long courseId) {
        return ensureInTx(userId, courseId);
    }

    /**
     * 커밋 뒤 이벤트에서 부르는 입구. 앞 트랜잭션은 이미 끝났으므로 반드시 새 트랜잭션에서 등록·닫기를 커밋한다
     * (REQUIRED면 끝난 트랜잭션 자원에 참여해 커밋이 보장되지 않는다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TextbookLookup ensureAfterCommit(Long userId, Long courseId) {
        return ensureInTx(userId, courseId);
    }

    private TextbookLookup ensureInTx(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null || course.getStatus() == CourseStatus.ARCHIVED) {
            return null;
        }
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        TextbookLookup latest = lookupMapper.findLatestByCourse(courseId, userId);
        TextbookLookupPlanner.Desired desired = TextbookLookupPlanner.plan(course, materials, extracts, latest);
        if (latest != null && latest.getBasisKey().equals(desired.basisKey()) && isLive(latest)) {
            return latest;
        }
        lookupMapper.supersedeOpen(courseId, userId, "교재 근거가 바뀜");
        if (latest != null && isLive(latest) && !latest.isOpen()) {
            lookupMapper.markSuperseded(latest.getLookupId());
        }
        return switch (desired.kind()) {
            case NONE, DISABLED -> null;
            case CONFLICT -> insert(course, desired, TextbookLookup.CLUE_CONFLICT, conflictResult(desired), false, null);
            case NEEDS_CLUE -> insert(course, desired, TextbookLookup.QUEUED, null, false, null);
            case QUERY -> {
                TextbookLookup reusable = desired.queryKey() == null ? null
                        : lookupMapper.findReusable(userId, desired.queryKey(), LocalDateTime.now().minusDays(reuseDays),
                        LocalDateTime.now().minusHours(notFoundReuseHours));
                yield reusable == null ? insert(course, desired, TextbookLookup.QUEUED, null, false, null)
                        : copyOf(course, desired, reusable);
            }
        };
    }

    /** 지금 basis에 대해 검색을 다시 한다(재사용·페이지 캐시를 건너뛴다). */
    @Transactional
    public TextbookLookup retry(Long userId, Long courseId) {
        Course course = owned(userId, courseId);
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        TextbookLookup latest = lookupMapper.findLatestByCourse(courseId, userId);
        TextbookLookupPlanner.Desired desired = TextbookLookupPlanner.plan(course, materials, extracts, latest);
        if (desired.kind() == TextbookLookupPlanner.Kind.NONE || desired.kind() == TextbookLookupPlanner.Kind.DISABLED
                || desired.kind() == TextbookLookupPlanner.Kind.CONFLICT) {
            return ensure(userId, courseId);
        }
        if (latest != null && latest.isOpen() && latest.getBasisKey().equals(desired.basisKey())) {
            return latest;
        }
        lookupMapper.supersedeOpen(courseId, userId, "다시 찾기");
        if (latest != null && isLive(latest)) {
            lookupMapper.markSuperseded(latest.getLookupId());
        }
        if ("USER_LINK".equals(desired.origin())) {
            TextbookLookup again = TextbookLookup.builder().userId(userId).courseId(courseId)
                    .basisKey(latest.getBasisKey()).queryKey(latest.getQueryKey()).queryJson(latest.getQueryJson())
                    .clueOrigin("USER_LINK").textbookVersion(desired.textbookVersion()).forceRefresh(true)
                    .status(TextbookLookup.QUEUED).build();
            lookupMapper.insert(again);
            return again;
        }
        return insert(course, desired, TextbookLookup.QUEUED, null, true, null);
    }

    /** 사용자가 준 상세 페이지 링크로 찾는다. 링크는 외부 입력 — 주소 검사는 등록할 때 한 번, 받을 때 다시. */
    @Transactional
    public TextbookLookup link(Long userId, Long courseId, String rawUrl) {
        URI uri;
        try {
            uri = SafePageFetcher.normalize(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.TEXTBOOK_LINK_INVALID);
        }
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (Boolean.FALSE.equals(course.getTextbookWebLookupEnabled())) {
            throw new ConflictException(ErrorCode.TEXTBOOK_WEB_DISABLED);
        }
        String url = uri.toString();
        lookupMapper.supersedeOpen(courseId, userId, "링크로 찾기");
        TextbookLookup latest = lookupMapper.findLatestByCourse(courseId, userId);
        if (latest != null && isLive(latest)) {
            lookupMapper.markSuperseded(latest.getLookupId());
        }
        BookMatcher.Clue clue = TextbookLookupPlanner.currentClue(course);
        LookupResult.Query query = new LookupResult.Query(clue.title(), clue.author(), clue.publisher(), clue.isbn(),
                clue.edition(), url, null);
        TextbookLookup lookup = TextbookLookup.builder().userId(userId).courseId(courseId)
                .basisKey(TextbookLookupPlanner.linkBasis(course, url)).queryKey(TextbookLookupPlanner.linkQueryKey(url))
                .queryJson(write(query)).clueOrigin("USER_LINK")
                .textbookVersion(course.getTextbookVersion() == null ? 0 : course.getTextbookVersion())
                .forceRefresh(false).status(TextbookLookup.QUEUED).build();
        lookupMapper.insert(lookup);
        log.info("교재 링크 조회 등록: courseId={}, url={}", courseId, SafePageFetcher.masked(url));
        return lookup;
    }

    /** 사용자가 판(후보)을 골랐다 → 교재 칸에 그 판을 쓴다. */
    @Transactional
    public TextbookLookup choose(Long userId, Long courseId, Long lookupId, Long revisionId, Integer expectedVersion) {
        if (lookupId == null || revisionId == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Course locked = textbookWriter.lock(userId, courseId);
        TextbookLookup lookup = lookupMapper.findByIdAndUserId(lookupId, userId);
        if (lookup == null || !lookup.getCourseId().equals(courseId)) {
            throw new NotFoundException(ErrorCode.TEXTBOOK_LOOKUP_NOT_FOUND);
        }
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        TextbookLookup latest = lookupMapper.findLatestByCourse(courseId, userId);
        TextbookLookupPlanner.Desired desired = TextbookLookupPlanner.plan(locked, materials, extracts, latest);
        boolean current = lookup.getBasisKey().equals(desired.basisKey()) && isLive(lookup)
                && List.of(TextbookLookup.FOUND, TextbookLookup.NEEDS_CHOICE, TextbookLookup.BOOK_NO_TOC)
                .contains(lookup.getStatus());
        if (!current) {
            throw new ConflictException(ErrorCode.TEXTBOOK_CANDIDATE_NOT_ALLOWED);
        }
        LookupResult result = read(lookup.getResultJson());
        LookupResult.Candidate picked = result == null || result.candidates() == null ? null
                : result.candidates().stream().filter(c -> revisionId.equals(c.revisionId())).findFirst().orElse(null);
        boolean allowed = picked != null && ("MATCH".equals(picked.verdict()) || "LINK".equals(picked.verdict()));
        TextbookWebRevision revision = allowed ? webMapper.findRevision(revisionId, userId) : null;
        if (revision == null) {
            throw new ConflictException(ErrorCode.TEXTBOOK_CANDIDATE_NOT_ALLOWED);
        }
        CourseTextbookWriter.Values next = new CourseTextbookWriter.Values(
                firstNonBlank(revision.getTitle(), locked.getTextbookTitle()),
                firstNonBlank(revision.getAuthors(), locked.getTextbookAuthor()),
                firstNonBlank(revision.getPublisher(), locked.getTextbookPublisher()),
                firstNonBlank(revision.getIsbn13(), locked.getTextbookIsbn()),
                firstNonBlank(revision.getEdition(), sameIsbn(revision, locked) ? locked.getTextbookEdition() : null));
        textbookWriter.write(locked, expectedVersion, next, CourseTextbookWriter.SOURCE_WEB, null, revisionId);
        lookupMapper.setChosen(lookupId, revisionId);
        lookupMapper.markSuperseded(lookupId);
        // 새 판의 조회를 같은 트랜잭션에서 바로 만든다 — 같은 판을 다시 검색하지 않고, 고른 판만 남긴다.
        Course after = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        TextbookLookup newLatest = lookupMapper.findLatestByCourse(courseId, userId);
        TextbookLookupPlanner.Desired now = TextbookLookupPlanner.plan(after, materials, extracts, newLatest);
        lookupMapper.supersedeOpen(courseId, userId, "판 선택");
        LookupResult chosenOnly = onlyEditionOf(result, revision);
        boolean hasToc = revision.getTocEntryCount() > 0;
        TextbookLookup chosen = insert(after, now, hasToc ? TextbookLookup.FOUND : TextbookLookup.BOOK_NO_TOC,
                chosenOnly, false, hasToc ? "PENDING" : "NONE");
        lookupMapper.setChosen(chosen.getLookupId(), revisionId);
        log.info("교재 판 선택: courseId={}, lookupId={}, revisionId={}", courseId, lookupId, revisionId);
        return chosen;
    }

    /** 외부 조회 켜기/끄기. 끄면 열린 조회를 닫는다(아직 보내지 않은 단서는 보내지 않는다). */
    @Transactional
    public void setEnabled(Long userId, Long courseId, boolean enabled) {
        owned(userId, courseId);
        courseMapper.updateTextbookWebLookupEnabled(courseId, userId, enabled);
        if (!enabled) {
            lookupMapper.supersedeOpen(courseId, userId, "웹 검색 끔");
        }
        events.publishEvent(new TextbookChangedEvent(userId, courseId, null));
    }

    // ===== worker가 쓰는 짧은 트랜잭션 =====

    /** 지금 basis와 같은가(읽기만). worker가 외부 호출 전에 본다. */
    @Transactional(readOnly = true)
    public boolean isCurrent(TextbookLookup lookup) {
        Course course = courseMapper.findByIdAndUserId(lookup.getCourseId(), lookup.getUserId());
        if (course == null || course.getStatus() == CourseStatus.ARCHIVED) {
            return false;
        }
        return currentBasis(course, lookup).equals(lookup.getBasisKey());
    }

    private String currentBasis(Course course, TextbookLookup lookup) {
        if ("USER_LINK".equals(lookup.getClueOrigin())) {
            if (Boolean.FALSE.equals(course.getTextbookWebLookupEnabled())) {
                return "";
            }
            LookupResult.Query q = readQuery(lookup.getQueryJson());
            int version = course.getTextbookVersion() == null ? 0 : course.getTextbookVersion();
            return q == null || q.link() == null || !Objects.equals(version, lookup.getTextbookVersion()) ? ""
                    : TextbookLookupPlanner.linkBasis(course, q.link());
        }
        TextbookExtracts.Materials materials = extracts.load(lookup.getUserId(), lookup.getCourseId());
        return TextbookLookupPlanner.plan(course, materials, extracts, null).basisKey();
    }

    /**
     * 결과 저장. 임대가 그대로이고 basis가 지금과 같을 때만 쓴다. 아니면 SUPERSEDED로 닫고 false.
     */
    @Transactional
    public boolean finishIfCurrent(TextbookLookup lookup, String status, LookupResult result, LookupResult.Query query,
                                   String errorCode, String errorMessage, String autoTidyState) {
        // 잠금 순서는 다른 경로(등록·판 선택)와 같게 과목 → 조회다.
        Course course = courseMapper.findByIdAndUserIdForUpdate(lookup.getCourseId(), lookup.getUserId());
        if (lookupMapper.lockIfLeased(lookup.getLookupId(), lookup.getLeaseToken()) == null) {
            return false;
        }
        if (course == null || !currentBasis(course, lookup).equals(lookup.getBasisKey())) {
            lookupMapper.markSuperseded(lookup.getLookupId());
            log.info("교재 조회 결과를 버린다(근거가 바뀜): lookupId={}", lookup.getLookupId());
            return false;
        }
        int rows = lookupMapper.finish(lookup.getLookupId(), lookup.getLeaseToken(), status,
                result == null ? null : write(result), query == null ? null : write(query), errorCode,
                cut(errorMessage, 500), autoTidyState);
        if (rows == 1 && TextbookLookup.FOUND.equals(status)) {
            adoptRefreshedRevision(course, result);
        }
        return rows == 1;
    }

    /**
     * 고른 판과 같은 ISBN의 목차를 새로 읽었으면(다시 찾기) 교재 칸의 웹 근거를 그 리비전으로 옮긴다.
     * 그래야 이후 조회가 못 찾음·실패로 끝나도 옛 리비전으로 되돌아가지 않는다. 과목을 잠근 채 부른다.
     */
    private void adoptRefreshedRevision(Course course, LookupResult result) {
        Long chosenId = course.getTextbookWebRevisionId();
        if (chosenId == null || result == null || result.editions() == null || result.editions().size() != 1) {
            return;
        }
        Long freshId = result.editions().get(0).bestRevisionId();
        if (freshId == null || freshId.equals(chosenId)) {
            return;
        }
        TextbookWebRevision chosen = webMapper.findRevision(chosenId, course.getUserId());
        TextbookWebRevision fresh = webMapper.findRevision(freshId, course.getUserId());
        if (chosen == null || fresh == null || chosen.getIsbn13() == null || !chosen.getIsbn13().equals(fresh.getIsbn13())
                || fresh.getTocEntryCount() < 1) {
            return;
        }
        if (courseMapper.moveTextbookWebRevision(course.getCourseId(), course.getUserId(), chosenId, freshId) == 1) {
            course.setTextbookWebRevisionId(freshId);
            log.info("같은 판의 새 목차로 웹 근거를 옮김: courseId={}, {} → {}", course.getCourseId(), chosenId, freshId);
        }
    }

    @Transactional
    public boolean reschedule(TextbookLookup lookup, LocalDateTime at, String errorCode, String message) {
        return lookupMapper.reschedule(lookup.getLookupId(), lookup.getLeaseToken(), at, errorCode, cut(message, 500)) == 1;
    }

    @Transactional
    public void supersede(TextbookLookup lookup) {
        lookupMapper.markSuperseded(lookup.getLookupId());
    }

    @Transactional
    public void updateQuery(TextbookLookup lookup, LookupResult.Query query) {
        lookupMapper.updateQueryJson(lookup.getLookupId(), lookup.getLeaseToken(), write(query));
    }

    /** 모델 보조 단서를 저장한 뒤 이 작업이 어떻게 되었나. */
    public enum ClueNext {
        /** 그 책을 찾는다(같은 작업이 새 근거로 이어 간다). */
        SEARCH,
        /** 교재 칸에서 책을 찾지 못했다 — NOT_FOUND로 끝냈다. */
        NO_BOOK,
        /** 서로 다른 주교재가 여럿이다 — CLUE_CONFLICT로 끝냈다(고르게 한다). */
        CONFLICT,
        /** 그 사이 근거가 바뀌었다(사용자가 교재를 정함 등) — 지난 작업이다. */
        STALE
    }

    public record ClueOutcome(ClueNext next, TextbookLookup lookup) {
    }

    /**
     * 모델 보조로 교재 단서를 저장한 뒤: 지금 근거는 단서 결과에 따라 "그 책 찾기"·"책 없음"·"주교재 여럿"으로 바뀐다. 같은
     * 작업의 basis를 그 근거로 다시 적고(과목 잠금 → 임대 대조) 책 없음·주교재 여럿은 이 트랜잭션에서 끝낸다.
     */
    @Transactional
    public ClueOutcome afterClue(TextbookLookup lookup, Long extractId, String clueJson) {
        Course course = courseMapper.findByIdAndUserIdForUpdate(lookup.getCourseId(), lookup.getUserId());
        if (course == null || lookupMapper.lockIfLeased(lookup.getLookupId(), lookup.getLeaseToken()) == null) {
            return new ClueOutcome(ClueNext.STALE, null);
        }
        if (extractId != null && clueJson != null) {
            extractMapper.updateClueJsonIfEmpty(extractId, lookup.getUserId(), clueJson);
        }
        TextbookExtracts.Materials materials = extracts.load(lookup.getUserId(), lookup.getCourseId());
        TextbookLookupPlanner.Desired desired = TextbookLookupPlanner.plan(course, materials, extracts, null);
        if (desired.kind() == TextbookLookupPlanner.Kind.NONE || desired.kind() == TextbookLookupPlanner.Kind.CONFLICT) {
            boolean none = desired.kind() == TextbookLookupPlanner.Kind.NONE;
            lookupMapper.rebase(lookup.getLookupId(), lookup.getLeaseToken(), desired.basisKey(),
                    TextbookLookupPlanner.hash("none|" + desired.basisKey()), lookup.getQueryJson(), lookup.getClueMaterialId(),
                    lookup.getClueFileHash(), lookup.getClueExtractorVersion(), desired.textbookVersion());
            LookupResult result = none
                    ? new LookupResult(null, null, List.of(), List.of(), List.of(), List.of(),
                    "강의계획서의 교재 칸에서 책 이름을 읽지 못했어요.")
                    : conflictResult(desired);
            lookupMapper.finish(lookup.getLookupId(), lookup.getLeaseToken(),
                    none ? TextbookLookup.NOT_FOUND : TextbookLookup.CLUE_CONFLICT, write(result), null,
                    none ? "CLUE_NOT_FOUND" : null, null, null);
            return new ClueOutcome(none ? ClueNext.NO_BOOK : ClueNext.CONFLICT, null);
        }
        if (desired.kind() != TextbookLookupPlanner.Kind.QUERY) {
            lookupMapper.markSuperseded(lookup.getLookupId());
            return new ClueOutcome(ClueNext.STALE, null);
        }
        BookMatcher.Clue clue = desired.clue();
        LookupResult.Query query = new LookupResult.Query(clue.title(), clue.author(), clue.publisher(), clue.isbn(),
                clue.edition(), null, null);
        if (lookupMapper.rebase(lookup.getLookupId(), lookup.getLeaseToken(), desired.basisKey(), desired.queryKey(),
                write(query), desired.clueMaterialId(), desired.clueFileHash(), desired.clueExtractorVersion(),
                desired.textbookVersion()) != 1) {
            return new ClueOutcome(ClueNext.STALE, null);
        }
        lookup.setBasisKey(desired.basisKey());
        lookup.setQueryKey(desired.queryKey());
        lookup.setQueryJson(write(query));
        lookup.setClueOrigin(desired.origin());
        lookup.setClueMaterialId(desired.clueMaterialId());
        lookup.setClueFileHash(desired.clueFileHash());
        lookup.setTextbookVersion(desired.textbookVersion());
        return new ClueOutcome(ClueNext.SEARCH, lookup);
    }

    /** 임대를 늘린다. 1이면 아직 이 worker의 작업이다 — 0이면 외부 호출을 멈춘다(다른 worker가 집었거나 닫혔다). */
    @Transactional
    public boolean renewLease(TextbookLookup lookup, int seconds) {
        return lookupMapper.renewLease(lookup.getLookupId(), lookup.getLeaseToken(),
                LocalDateTime.now().plusSeconds(seconds)) == 1;
    }

    /** 하루 외부 호출 예약. 상한이면 false — 호출하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveCalls(Long userId, int n) {
        LocalDate today = LocalDate.now();
        lookupMapper.ensureUsageRow(userId, today);
        return lookupMapper.reserveUsage(userId, today, n, dailyCallLimit) == 1;
    }

    // ===== 읽기 =====

    public LookupResult read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LookupResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    public LookupResult.Query readQuery(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LookupResult.Query.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 도움 =====

    private TextbookLookup insert(Course course, TextbookLookupPlanner.Desired desired, String status,
                                  LookupResult result, boolean force, String autoTidy) {
        BookMatcher.Clue clue = desired.clue();
        LookupResult.Query query = new LookupResult.Query(clue == null ? null : clue.title(),
                clue == null ? null : clue.author(), clue == null ? null : clue.publisher(),
                clue == null ? null : clue.isbn(), clue == null ? null : clue.edition(), desired.linkUrl(),
                desired.kind() == TextbookLookupPlanner.Kind.NEEDS_CLUE ? Boolean.TRUE : null);
        boolean finished = !TextbookLookup.QUEUED.equals(status);
        TextbookLookup lookup = TextbookLookup.builder().userId(course.getUserId()).courseId(course.getCourseId())
                .basisKey(desired.basisKey())
                .queryKey(desired.queryKey() == null ? TextbookLookupPlanner.hash("none|" + desired.basisKey()) : desired.queryKey())
                .queryJson(write(query))
                .clueOrigin(desired.origin() == null ? "SYLLABUS" : desired.origin())
                .clueMaterialId(desired.clueMaterialId()).clueFileHash(desired.clueFileHash())
                .clueExtractorVersion(desired.clueExtractorVersion()).textbookVersion(desired.textbookVersion())
                .forceRefresh(force).status(status).resultJson(result == null ? null : write(result))
                .autoTidyState(autoTidy).finishedAt(finished ? LocalDateTime.now() : null).build();
        try {
            lookupMapper.insert(lookup);
        } catch (DuplicateKeyException e) {
            // 열린 조회가 이미 있다(다른 요청이 먼저 등록). 그 행을 쓴다.
            return lookupMapper.findLatestByCourse(course.getCourseId(), course.getUserId());
        }
        return lookup;
    }

    private TextbookLookup copyOf(Course course, TextbookLookupPlanner.Desired desired, TextbookLookup from) {
        LookupResult result = read(from.getResultJson());
        boolean hasToc = TextbookLookup.FOUND.equals(from.getStatus());
        TextbookLookup copy = insert(course, desired, from.getStatus(),
                result == null ? null : new LookupResult(result.clue(), "reused", result.candidates(), result.editions(),
                        result.failures(), result.clueOptions(), result.note()),
                false, hasToc ? "PENDING" : "NONE");
        log.info("교재 조회 재사용: courseId={}, from={}, status={}", course.getCourseId(), from.getLookupId(), from.getStatus());
        return copy;
    }

    private LookupResult conflictResult(TextbookLookupPlanner.Desired desired) {
        List<LookupResult.SyllabusOption> options = new ArrayList<>();
        for (TextbookLookupPlanner.SourcedClue c : TextbookLookupPlanner.mains(desired.syllabusClues())) {
            options.add(new LookupResult.SyllabusOption(c.clue().title(), c.clue().author(), c.clue().publisher(),
                    c.clue().isbn(), c.clue().edition(), c.clue().role(), c.materialId(), c.filename()));
        }
        return new LookupResult(null, null, List.of(), List.of(), List.of(), options,
                "자료마다 다른 주교재가 적혀 있어요. 지금 쓰는 교재를 골라 주세요.");
    }

    private static LookupResult onlyEditionOf(LookupResult result, TextbookWebRevision revision) {
        if (result == null) {
            return null;
        }
        List<LookupResult.Edition> editions = result.editions() == null ? List.of() : result.editions().stream()
                .filter(e -> e.revisionIds() != null && e.revisionIds().contains(revision.getRevisionId())).toList();
        List<Long> keep = editions.isEmpty() ? List.of(revision.getRevisionId()) : editions.get(0).revisionIds();
        List<LookupResult.Candidate> candidates = result.candidates() == null ? List.of() : result.candidates().stream()
                .filter(c -> keep.contains(c.revisionId())).toList();
        return new LookupResult(result.clue(), "chosen", candidates, editions, List.of(), List.of(), null);
    }

    private static boolean sameIsbn(TextbookWebRevision revision, Course course) {
        return revision.getIsbn13() != null && course.getTextbookIsbn() != null
                && revision.getIsbn13().equals(BookPageParser.toIsbn13(BookPageParser.isbnOrNull(course.getTextbookIsbn())));
    }

    private Course owned(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    static boolean isLive(TextbookLookup lookup) {
        return !TextbookLookup.SUPERSEDED.equals(lookup.getStatus()) && !TextbookLookup.CANCELLED.equals(lookup.getStatus());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b == null || b.isBlank() ? null : b;
    }

    private static String cut(String v, int max) {
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }
}
