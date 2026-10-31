package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.web.LookupResult;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookup;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupPlanner;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupService;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebRevision;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 프로젝트의 교재: 어느 책인가(서지)와 그 책이 다루는 범위(목차).
 *
 * <ul>
 *   <li><b>조용히 덮지 않는다.</b> 자료·웹에서 찾은 값은 후보다. 교재 칸은 {@link CourseTextbookWriter}로만 바뀌고, 화면이 본
 *       판·값과 다르면 409로 멈춘다.</li>
 *   <li><b>목차를 상상하지 않는다.</b> 책 이름만 있으면 웹에서 실제 목차 원문을 찾고(서버 백그라운드), 못 찾으면 그렇다고 말한다.
 *       계획·학습은 그대로 된다.</li>
 *   <li><b>같은 파일은 한 번만.</b> 규칙 추출은 (자료, 해시, 추출 판)마다 한 행({@link TextbookExtracts}).</li>
 *   <li>웹 조회의 상태는 {@code textbook_lookups}가 원본이다 — 화면을 닫거나 새로고침해도 이어진다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextbookService {

    static final List<String> FIELDS = List.of("title", "author", "publisher", "isbn", "edition");

    private final CourseMapper courseMapper;
    private final CourseTopicMapper topicMapper;
    private final TextbookExtracts extracts;
    private final TocResolver tocResolver;
    private final TextbookLookupService lookupService;
    private final TextbookLookupMapper lookupMapper;
    private final TextbookWebMapper webMapper;
    private final CourseTextbookWriter textbookWriter;

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    // ===== 조회 =====

    /** 교재 구역. 지금 근거에 맞는 웹 조회가 없으면 등록만 한다(외부 호출은 worker가 한다). */
    @Transactional
    public TextbookReview review(Long userId, Long courseId) {
        owned(userId, courseId);
        lookupService.ensure(userId, courseId);
        return build(userId, courseId);
    }

    private TextbookReview build(Long userId, Long courseId) {
        Course course = owned(userId, courseId);
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        Map<String, String> current = currentValues(course);

        List<TextbookReview.Candidate> candidates = new ArrayList<>();
        for (MaterialTextbookExtract extract : materials.extracts()) {
            Map<String, TextbookExtractor.Field> book = extracts.book(extract);
            if (book.isEmpty()) {
                continue;
            }
            CourseMaterial material = materials.byId().get(extract.getMaterialId());
            List<TextbookReview.FieldCandidate> fields = new ArrayList<>();
            for (String key : FIELDS) {
                TextbookExtractor.Field field = book.get(key);
                if (field == null) {
                    continue;
                }
                fields.add(new TextbookReview.FieldCandidate(key, field.value(), field.unit(), field.quote(),
                        current.get(key), same(key, field.value(), current.get(key))));
            }
            candidates.add(new TextbookReview.Candidate(extract.getMaterialId(),
                    material == null ? null : material.getOriginalFilename(),
                    materials.types().get(extract.getMaterialId()), fields));
        }

        List<TextbookReview.SyllabusClue> clues = new ArrayList<>();
        for (TextbookLookupPlanner.SourcedClue c : TextbookLookupPlanner.syllabusClues(materials, extracts)) {
            TextbookExtractor.BookClue b = c.clue();
            boolean sameAsCurrent = BookKey.of(course) != null && BookKey.sameBook(b.title(), b.isbn(), b.edition(),
                    course.getTextbookTitle(), course.getTextbookIsbn(), course.getTextbookEdition());
            clues.add(new TextbookReview.SyllabusClue(c.materialId(), c.filename(), b.role(), b.title(), b.author(),
                    b.publisher(), b.isbn(), b.edition(), b.unit(), b.quote(), c.source(), sameAsCurrent));
        }

        TocResolver.Resolution resolution = tocResolver.resolve(course, materials);
        TocSnapshot snapshot = resolution.toc();
        TextbookReview.Toc toc = snapshot == null
                ? new TextbookReview.Toc("NOT_FOUND", null, null, 0, null, null, List.of(), null, null, null, null, null)
                : new TextbookReview.Toc("FOUND", snapshot.materialId(), snapshot.filename(), snapshot.entries().size(),
                snapshot.fromUnit(), snapshot.toUnit(), snapshot.entries(), snapshot.kind(), snapshot.label(),
                snapshot.coverage(), snapshot.sourceUrl(), snapshot.fetchedAt(), snapshot.unread());

        TextbookLookup latest = lookupMapper.findLatestByCourse(courseId, userId);
        TextbookReview.Lookup lookup = latest == null || TextbookLookup.SUPERSEDED.equals(latest.getStatus())
                || TextbookLookup.CANCELLED.equals(latest.getStatus()) ? null : lookupView(latest);
        boolean enabled = !Boolean.FALSE.equals(course.getTextbookWebLookupEnabled());

        boolean hasBookIdentity = BookKey.of(course) != null || !clues.isEmpty()
                || candidates.stream().anyMatch(c -> c.fields().stream()
                .anyMatch(f -> f.field().equals("title") || f.field().equals("isbn")));
        String state = "FOUND".equals(toc.status()) ? "TOC_FOUND" : hasBookIdentity ? "TITLE_ONLY" : "NONE";
        int topics = topicMapper.findActiveByCourseIdAndUserId(courseId, userId).size();
        return new TextbookReview(courseId, TextbookReview.Current.of(course, webSource(course)), candidates, toc, state,
                nextAction(state, topics, materials.pending(), lookup, enabled, !resolution.unlinked().isEmpty()),
                topics, materials.pending(), clues, lookup, resolution.unlinked(), enabled);
    }

    private TextbookReview.WebSource webSource(Course course) {
        if (course.getTextbookWebRevisionId() == null) {
            return null;
        }
        TextbookWebRevision r = webMapper.findRevision(course.getTextbookWebRevisionId(), course.getUserId());
        if (r == null) {
            return null;
        }
        return new TextbookReview.WebSource(r.getRevisionId(), r.getSite(),
                "SHARED".equals(r.getCacheScopeKey()) ? r.getUrl() : com.jungwoo.project.memo.course.textbook.web.SafePageFetcher.masked(r.getUrl()),
                r.getFetchedAt() == null ? null : r.getFetchedAt().format(AT), r.getIsbn13(), r.getPublishedDate());
    }

    private TextbookReview.Lookup lookupView(TextbookLookup l) {
        LookupResult result = lookupService.read(l.getResultJson());
        LookupResult.Query query = lookupService.readQuery(l.getQueryJson());
        if (query != null && query.link() != null) {
            query = new LookupResult.Query(query.title(), query.author(), query.publisher(), query.isbn(), query.edition(),
                    com.jungwoo.project.memo.course.textbook.web.SafePageFetcher.masked(query.link()), query.needsClue());
        }
        return new TextbookReview.Lookup(l.getLookupId(), l.getStatus(), l.getClueOrigin(), query,
                result == null ? null : result.searchedWith(),
                result == null || result.editions() == null ? List.of() : result.editions(),
                result == null || result.candidates() == null ? List.of() : result.candidates(),
                result == null || result.failures() == null ? List.of() : result.failures(),
                result == null || result.clueOptions() == null ? List.of() : result.clueOptions(),
                result == null ? null : result.note(), l.getErrorCode(),
                l.getCreatedAt() == null ? null : l.getCreatedAt().format(AT),
                l.getFinishedAt() == null ? null : l.getFinishedAt().format(AT), l.getAutoTidyState(),
                l.getChosenRevisionId(), l.getReusedFromLookupId() != null);
    }

    static String nextAction(String state, int topics, int pending, TextbookReview.Lookup lookup, boolean enabled,
                             boolean hasUnlinkedToc) {
        String keepGoing = " 지금 자료로도 계획과 학습은 그대로 진행할 수 있어요.";
        String waiting = pending > 0 ? " 아직 읽는 중인 자료 " + pending + "개는 끝나면 다시 확인해요." : "";
        if ("TOC_FOUND".equals(state)) {
            return topics == 0
                    ? "목차로 학습 구조의 첫 골격을 만들 수 있어요. 정리 구역에서 변경안을 검토하고 고른 것만 적용하세요."
                    : "목차와 지금 학습 구조를 비교해 빠진 장·절만 변경안으로 받을 수 있어요. 정리 구역에서 검토하세요.";
        }
        if (hasUnlinkedToc) {
            return "올린 목차가 어느 책의 것인지 적혀 있지 않아요. 지금 교재의 목차가 맞으면 [이 교재 목차로 쓰기]를 눌러 주세요."
                    + keepGoing;
        }
        if (lookup != null) {
            String status = lookup.status();
            if (TextbookLookup.QUEUED.equals(status) || TextbookLookup.RUNNING.equals(status)) {
                return "교재와 목차를 웹에서 찾는 중이에요. 화면을 닫아도 서버에서 계속돼요." + keepGoing;
            }
            switch (status) {
                case TextbookLookup.NEEDS_CHOICE:
                    // 판이 하나뿐인데 고르라는 것은 사용자가 준 링크라 서버가 같은 책인지 확인하지 못한 경우다.
                    return (lookup.editions().size() == 1
                            ? "준 링크의 책이 지금 교재와 같은지 확인하지 못했어요. 맞으면 이 판으로 정해 주세요."
                            : "같은 제목의 책이 " + lookup.editions().size() + "가지(판·발행일이 달라요) 있어요. 지금 쓰는 판을 골라 주세요.")
                            + keepGoing;
                case TextbookLookup.BOOK_NO_TOC:
                    return "책은 찾았지만 찾은 페이지에 목차가 없어요. 목차 쪽 사진·PDF를 올리거나 목차가 있는 상세 페이지 링크를 주세요."
                            + keepGoing;
                case TextbookLookup.NOT_FOUND:
                    return "이 정보로 맞는 책 페이지를 찾지 못했어요(책이 없다는 뜻은 아니에요). ISBN·판을 적거나 출판사·서점 링크를 주세요."
                            + keepGoing;
                case TextbookLookup.ACCESS_FAILED:
                    return "찾은 페이지에 접속하지 못했어요. 잠시 뒤 [다시 찾기]를 누르거나 다른 링크를 주세요." + keepGoing;
                case TextbookLookup.FAILED:
                    return "교재를 찾는 중 문제가 생겼어요. [다시 찾기]를 눌러 주세요." + keepGoing;
                case TextbookLookup.CLUE_CONFLICT:
                    return "자료마다 다른 주교재가 적혀 있어요. 지금 쓰는 교재를 골라 주세요." + keepGoing;
                case TextbookLookup.FOUND:
                    return "책은 찾았지만 목차를 쓰기 전에 확인할 것이 있어요." + keepGoing;
                default:
                    break;
            }
        }
        if (!enabled && "TITLE_ONLY".equals(state)) {
            return "웹 검색을 꺼 두어 목차를 찾지 않았어요. 목차 쪽(사진·PDF)을 올리면 범위를 확인할 수 있어요." + keepGoing + waiting;
        }
        if ("TITLE_ONLY".equals(state)) {
            return "교재 목차를 아직 확보하지 못했어요. 목차 쪽(사진·PDF)이나 본문을 올리면 범위를 확인할 수 있어요."
                    + keepGoing + waiting;
        }
        return "교재 정보가 없어요. 교재를 쓰는 과목이면 이름을 적거나 목차·판권 쪽을 올려 주세요. 없어도 계획·학습은 할 수 있어요." + waiting;
    }

    // ===== 적용(자료 후보) =====

    /**
     * 자료에서 찾은 값 중 사용자가 고른 칸만 교재 칸에 넣는다.
     *
     * @param expected        화면이 본 지금 교재 칸 값(고른 칸만). 지금 값과 다르면 409 — 그 사이 사용자가 고친 값을 덮지 않는다
     * @param values          화면이 본 후보 값(고른 칸만). 지금 추출한 후보와 다르면 409
     * @param expectedVersion 화면이 본 교재 판(없으면 칸 값 대조만). 다르면 409
     */
    @Transactional
    public TextbookReview apply(Long userId, Long courseId, Long materialId, Map<String, String> values,
                                Map<String, String> expected, Integer expectedVersion) {
        if (materialId == null || values == null || values.isEmpty()
                || !FIELDS.containsAll(values.keySet())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Course course = textbookWriter.lock(userId, courseId);
        Map<String, String> current = currentValues(course);
        for (String key : values.keySet()) {
            String seen = expected == null ? null : blankToNull(expected.get(key));
            if (!Objects.equals(seen, current.get(key))) {
                throw new ConflictException(ErrorCode.TEXTBOOK_INFO_CHANGED);
            }
        }
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        MaterialTextbookExtract extract = materials.extractOf(materialId);
        if (extract == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        Map<String, TextbookExtractor.Field> book = extracts.book(extract);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            TextbookExtractor.Field found = book.get(entry.getKey());
            if (found == null || !found.value().equals(entry.getValue())) {
                throw new ConflictException(ErrorCode.TEXTBOOK_CANDIDATE_CHANGED);
            }
        }
        textbookWriter.write(course, expectedVersion, merged(current, values), CourseTextbookWriter.SOURCE_MATERIAL,
                materialId, null);
        log.info("교재 정보 적용: courseId={}, materialId={}, fields={}", courseId, materialId, values.keySet());
        return build(userId, courseId);
    }

    /**
     * 강의계획서가 적은 교재 단서 하나를 지금 교재로 정한다(주교재가 여럿일 때 고르기, "이 교재로 정하기").
     * 그 자료에서 실제로 읽은 단서와 같을 때만.
     */
    @Transactional
    public TextbookReview applyClue(Long userId, Long courseId, Long materialId, String title, Integer expectedVersion) {
        if (materialId == null || title == null || title.isBlank()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (expectedVersion == null) {
            throw new BadRequestException(ErrorCode.TEXTBOOK_VERSION_REQUIRED);
        }
        Course course = textbookWriter.lock(userId, courseId);
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        TextbookExtractor.BookClue clue = TextbookLookupPlanner.syllabusClues(materials, extracts).stream()
                .filter(c -> materialId.equals(c.materialId()) && title.equals(c.clue().title()))
                .map(TextbookLookupPlanner.SourcedClue::clue).findFirst().orElse(null);
        if (clue == null) {
            throw new ConflictException(ErrorCode.TEXTBOOK_CANDIDATE_CHANGED);
        }
        textbookWriter.write(course, expectedVersion, new CourseTextbookWriter.Values(clue.title(), clue.author(),
                clue.publisher(), clue.isbn(), clue.edition()), CourseTextbookWriter.SOURCE_MATERIAL, materialId, null);
        return build(userId, courseId);
    }

    /** 식별이 없는 업로드 목차를 "지금 교재의 목차"로 잇는다. */
    @Transactional
    public TextbookReview linkToc(Long userId, Long courseId, Long materialId, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException(ErrorCode.TEXTBOOK_VERSION_REQUIRED);
        }
        Course course = textbookWriter.lock(userId, courseId);
        if (BookKey.of(course) == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        TextbookExtracts.Materials materials = extracts.load(userId, courseId);
        MaterialTextbookExtract extract = materials.extractOf(materialId);
        if (extract == null || extract.getTocJson() == null || extract.getTocEntryCount() < 3) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        textbookWriter.linkToc(course, expectedVersion, materialId, extract.getFileHash());
        return build(userId, courseId);
    }

    public TextbookReview view(Long userId, Long courseId) {
        return build(userId, courseId);
    }

    // ===== 정리(tidy)가 쓰는 목차 =====

    /** 이 프로젝트에서 쓸 교재 목차. 없으면 null — 목차를 만들어 내지 않는다. */
    @Transactional
    public TocSnapshot tocOf(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            return null;
        }
        return tocResolver.resolve(course, extracts.load(userId, courseId)).toc();
    }

    /** 화면 조회용(읽기 전용 트랜잭션 안에서도 쓸 수 있다 — 추출을 새로 남기지 않는다). */
    public TocSnapshot tocOfReadOnly(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            return null;
        }
        return tocResolver.resolve(course, extracts.loadExisting(userId, courseId)).toc();
    }

    /**
     * 정리에 쓰는 목차.
     *
     * @param basis    이 목차의 근거. 정리안이 들고 있다가 적용할 때 지금 근거와 대조한다
     * @param kind     MATERIAL · WEB
     * @param label    출처 한 줄
     * @param coverage WEB일 때 페이지 목차를 얼마나 읽었나
     */
    public record TocSnapshot(Long materialId, String filename, String fileHash, Integer fromUnit, Integer toUnit,
                              List<TextbookExtractor.TocEntry> entries, TocResolver.Basis basis, String label,
                              String kind, String sourceUrl, String fetchedAt, String coverage, String tocRawHash,
                              int unread) {

        /** 목차 항목 열쇠의 판. 2 = 웹은 원문 줄 번호, 업로드는 추출 순번. 그 전(NULL)은 구조화 목록 순번이었다. */
        public static final int KEY_VERSION = 2;

        public TocSnapshot(Long materialId, String filename, String fileHash, Integer fromUnit, Integer toUnit,
                           List<TextbookExtractor.TocEntry> entries, TocResolver.Basis basis, String label,
                           String kind, String sourceUrl, String fetchedAt, String coverage) {
            this(materialId, filename, fileHash, fromUnit, toUnit, entries, basis, label, kind, sourceUrl, fetchedAt,
                    coverage, null, 0);
        }

        /** 예전 모양(업로드 목차). 근거는 자료·해시만. */
        public TocSnapshot(Long materialId, String filename, String fileHash, Integer fromUnit, Integer toUnit,
                           List<TextbookExtractor.TocEntry> entries) {
            this(materialId, filename, fileHash, fromUnit, toUnit, entries,
                    new TocResolver.Basis("MATERIAL", materialId, fileHash, null, null, null),
                    filename == null ? "교재 목차" : "「" + filename + "」 목차", "MATERIAL", null, null, null);
        }

        public boolean isWeb() {
            return "WEB".equals(kind);
        }

        /** 열쇠의 종류: WEB(원문 해시 + 원문 줄) · MATERIAL(파일 해시 + 추출 순번). */
        public String keyKind() {
            return isWeb() ? "WEB" : "MATERIAL";
        }

        /** 열쇠가 기대는 원문. 웹은 목차 원문 해시, 업로드는 파일 해시. */
        public String keyHash() {
            return isWeb() ? tocRawHash : fileHash;
        }

        /** index번째(0부터) 항목의 열쇠. 웹은 원문 줄 번호, 업로드는 1부터의 순번. */
        public int keyAt(int index) {
            return isWeb() ? entries.get(index).unit() : index + 1;
        }

        /** 열쇠로 항목의 위치(0부터)를 찾는다. 없으면 -1. 순번으로 찾지 않는다(웹은 같은 순번이 다른 줄일 수 있다). */
        public int indexOfKey(int key) {
            if (entries == null) {
                return -1;
            }
            if (!isWeb()) {
                return key >= 1 && key <= entries.size() ? key - 1 : -1;
            }
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).unit() == key) {
                    return i;
                }
            }
            return -1;
        }

        public TextbookExtractor.TocEntry entryByKey(int key) {
            int i = indexOfKey(key);
            return i < 0 ? null : entries.get(i);
        }

        /** 열쇠 → 표시 순번(1부터, 목차 안 위치). 화면의 "목차 N번째"에 쓴다. */
        public java.util.Map<Integer, Integer> ordinalByKey() {
            java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
            for (int i = 0; entries != null && i < entries.size(); i++) {
                out.put(keyAt(i), i + 1);
            }
            return out;
        }
    }

    // ===== 도움 =====

    private Course owned(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    private static CourseTextbookWriter.Values merged(Map<String, String> current, Map<String, String> values) {
        return new CourseTextbookWriter.Values(
                values.containsKey("title") ? cut(values.get("title"), 300) : current.get("title"),
                values.containsKey("author") ? cut(values.get("author"), 200) : current.get("author"),
                values.containsKey("publisher") ? cut(values.get("publisher"), 200) : current.get("publisher"),
                values.containsKey("isbn") ? cut(values.get("isbn"), 50) : current.get("isbn"),
                values.containsKey("edition") ? cut(values.get("edition"), 100) : current.get("edition"));
    }

    static Map<String, String> currentValues(Course course) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", blankToNull(course.getTextbookTitle()));
        values.put("author", blankToNull(course.getTextbookAuthor()));
        values.put("publisher", blankToNull(course.getTextbookPublisher()));
        values.put("isbn", blankToNull(course.getTextbookIsbn()));
        values.put("edition", blankToNull(course.getTextbookEdition()));
        return values;
    }

    /** 같은 값인가. 띄어쓰기·대소문자·ISBN의 하이픈은 무시한다. */
    static boolean same(String field, String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String y = b.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if ("isbn".equals(field)) {
            x = x.replace("-", "");
            y = y.replace("-", "");
        }
        return x.equals(y);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        String v = blankToNull(value);
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }
}
