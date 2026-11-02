package com.jungwoo.project.memo.course.textbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.web.LookupResult;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookup;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupPlanner;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebRevision;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 이 과목의 학습 구조에 쓸 교재 목차 하나를 고른다. 모델·외부 호출 없음.
 *
 * <p>"가장 긴 목차"가 아니라 <b>지금 교재의 것이라고 확인된 목차</b>를 고른다.
 * <ol>
 *   <li>사용자가 "이 교재의 목차"로 이은 업로드 자료 — 이을 때의 교재 식별이 지금과 같고 자료가 그대로일 때만.</li>
 *   <li>업로드 목차 중 그 자료의 판권면 식별(ISBN·제목)이 지금 교재와 같은 것.</li>
 *   <li>지금 교재(또는 교재 칸이 비면 강의계획서 주교재)로 찾은 웹 목차 — 그 조회의 근거가 지금과 같을 때만.</li>
 *   <li>교재 칸이 비어 있을 때만: 식별 없는 업로드 목차(예전 동작). 교재가 정해진 뒤에는 식별 없는 목차를 저절로 쓰지 않고
 *       화면이 "이 교재 목차로 쓰기"를 묻는다(이전 교재의 목차일 수 있다).</li>
 * </ol>
 * 결과에는 근거(basis)를 붙인다 — 정리안이 그 근거를 들고 있다가 적용할 때 지금 근거와 대조한다.
 */
@Component
@RequiredArgsConstructor
public class TocResolver {

    private final TextbookExtracts extracts;
    private final TextbookLookupMapper lookupMapper;
    private final TextbookWebMapper webMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * 목차의 근거. 비교는 {@link #sameAs}로 — 교재 판(textbookVersion)과 책 열쇠(bookKey)는 정보일 뿐 비교에서 뺀다(저자 표기만
     * 고치거나 같은 책에 ISBN을 채워도 판·열쇠가 바뀐다). 목차가 실제로 바뀌면 자료·해시·리비전이 다르다.
     */
    public record Basis(String kind, Long materialId, String fileHash, Long revisionId, Integer textbookVersion,
                        String bookKey) {

        public boolean sameAs(Basis other) {
            return other != null && Objects.equals(kind, other.kind) && Objects.equals(materialId, other.materialId)
                    && Objects.equals(fileHash, other.fileHash) && Objects.equals(revisionId, other.revisionId);
        }
    }

    /**
     * @param toc      고른 목차(없으면 null)
     * @param unlinked 교재가 정해져 있는데 식별이 없어 저절로 쓰지 않은 업로드 목차(사용자가 이을 수 있다)
     */
    public record Resolution(TextbookService.TocSnapshot toc, List<Unlinked> unlinked) {
    }

    public record Unlinked(Long materialId, String filename, int entryCount) {
    }

    public Resolution resolve(Course course, TextbookExtracts.Materials materials) {
        String currentKey = BookKey.of(course);
        int version = course.getTextbookVersion() == null ? 0 : course.getTextbookVersion();
        List<Unlinked> unlinked = new ArrayList<>();

        // 1. 사용자가 이은 목차
        if (course.getTextbookTocMaterialId() != null && Objects.equals(course.getTextbookTocBookKey(), currentKey)) {
            MaterialTextbookExtract e = materials.extractOf(course.getTextbookTocMaterialId());
            CourseMaterial m = materials.byId().get(course.getTextbookTocMaterialId());
            if (e != null && m != null && Objects.equals(m.getFileHash(), course.getTextbookTocFileHash())
                    && e.getTocJson() != null && e.getTocEntryCount() >= 3) {
                return new Resolution(fromMaterial(e, materials, version, currentKey), unlinked);
            }
        }

        List<MaterialTextbookExtract> withToc = materials.extracts().stream()
                .filter(e -> e.getTocEntryCount() >= 3 && e.getTocJson() != null).toList();

        if (currentKey == null) {
            // 4(교재 칸이 빔): 업로드 목차를 먼저(예전 동작), 없으면 강의계획서 단서로 찾은 웹 목차.
            MaterialTextbookExtract best = longest(withToc, materials);
            if (best != null) {
                return new Resolution(fromMaterial(best, materials, version, null), unlinked);
            }
            return new Resolution(fromWeb(course, materials, version, null), unlinked);
        }

        // 2. 판권면 식별이 지금 교재와 같은 업로드 목차
        List<MaterialTextbookExtract> matching = new ArrayList<>();
        for (MaterialTextbookExtract e : withToc) {
            Map<String, TextbookExtractor.Field> book = extracts.book(e);
            String title = value(book, "title");
            String isbn = value(book, "isbn");
            if (title == null && isbn == null) {
                CourseMaterial m = materials.byId().get(e.getMaterialId());
                unlinked.add(new Unlinked(e.getMaterialId(), m == null ? null : m.getOriginalFilename(), e.getTocEntryCount()));
                continue;
            }
            if (BookKey.sameBook(title, isbn, value(book, "edition"), course.getTextbookTitle(), course.getTextbookIsbn(),
                    course.getTextbookEdition())) {
                matching.add(e);
            }
        }
        MaterialTextbookExtract best = longest(matching, materials);
        if (best != null) {
            return new Resolution(fromMaterial(best, materials, version, currentKey), unlinked);
        }
        // 3. 지금 교재로 찾은 웹 목차
        return new Resolution(fromWeb(course, materials, version, currentKey), unlinked);
    }

    private MaterialTextbookExtract longest(List<MaterialTextbookExtract> list, TextbookExtracts.Materials materials) {
        return list.stream()
                .max(Comparator.comparing((MaterialTextbookExtract e) ->
                                MaterialType.TEXTBOOK_TOC.name().equals(materials.types().get(e.getMaterialId())))
                        .thenComparingInt(MaterialTextbookExtract::getTocEntryCount))
                .orElse(null);
    }

    private TextbookService.TocSnapshot fromMaterial(MaterialTextbookExtract e, TextbookExtracts.Materials materials,
                                                     int version, String bookKey) {
        TextbookExtracts.TocJson json = extracts.toc(e);
        CourseMaterial material = materials.byId().get(e.getMaterialId());
        String filename = material == null ? null : material.getOriginalFilename();
        return new TextbookService.TocSnapshot(e.getMaterialId(), filename, e.getFileHash(), json.fromUnit(), json.toUnit(),
                json.entries(), new Basis("MATERIAL", e.getMaterialId(), e.getFileHash(), null, version, bookKey),
                filename == null ? "교재 목차" : "「" + filename + "」 목차", "MATERIAL", null, null, null);
    }

    /**
     * 웹 목차. 사용자가 고른 판(교재 칸에 기록된 웹 근거)이 먼저다 — 그 뒤의 조회(다시 찾기·다른 링크)가 못 찾음·실패로
     * 끝나도 이미 정한 교재의 목차를 잃지 않는다. 교재 칸이 다른 책으로 바뀌면 그 근거는 쓰는 쪽(CourseTextbookWriter)이 비운다.
     * 고른 판이 없으면 지금 근거의 조회가 FOUND(또는 판을 고른 조회)일 때 그 판의 웹 목차.
     */
    private TextbookService.TocSnapshot fromWeb(Course course, TextbookExtracts.Materials materials, int version,
                                                String bookKey) {
        TextbookService.TocSnapshot fromLookup = fromLookup(course, materials, version, bookKey);
        if (bookKey == null || course.getTextbookWebRevisionId() == null) {
            return fromLookup;
        }
        TextbookService.TocSnapshot chosen = webSnapshot(course, course.getTextbookWebRevisionId(), version, bookKey);
        if (chosen == null) {
            return fromLookup;
        }
        // 지금 근거의 조회가 같은 판(ISBN)의 목차를 새로 확보했으면 그것이 최신이다(새 목차 알림·자동 정리가 바뀐 목차를 본다).
        // 다른 판이거나 못 찾음·실패·고르기 전이면 고른 판을 지킨다.
        if (fromLookup != null && !Objects.equals(fromLookup.basis().revisionId(), chosen.basis().revisionId())) {
            TextbookWebRevision picked = webMapper.findRevision(chosen.basis().revisionId(), course.getUserId());
            TextbookWebRevision latest = webMapper.findRevision(fromLookup.basis().revisionId(), course.getUserId());
            if (picked != null && latest != null && picked.getIsbn13() != null
                    && picked.getIsbn13().equals(latest.getIsbn13())) {
                return fromLookup;
            }
        }
        return chosen;
    }

    /** 지금 근거의 조회가 FOUND(또는 판을 고른 조회)일 때 그 판의 웹 목차. */
    private TextbookService.TocSnapshot fromLookup(Course course, TextbookExtracts.Materials materials, int version,
                                                   String bookKey) {
        TextbookLookup lookup = lookupMapper.findLatestByCourse(course.getCourseId(), course.getUserId());
        if (lookup == null || TextbookLookup.SUPERSEDED.equals(lookup.getStatus())
                || TextbookLookup.CANCELLED.equals(lookup.getStatus())) {
            return null;
        }
        TextbookLookupPlanner.Desired desired = TextbookLookupPlanner.plan(course, materials, extracts, lookup);
        if (!lookup.getBasisKey().equals(desired.basisKey())) {
            return null;
        }
        Long revisionId = lookup.getChosenRevisionId();
        if (revisionId == null && TextbookLookup.FOUND.equals(lookup.getStatus())) {
            LookupResult result = readResult(lookup.getResultJson());
            if (result != null && result.editions() != null && result.editions().size() == 1) {
                revisionId = result.editions().get(0).bestRevisionId();
            }
        }
        if (revisionId == null) {
            return null;
        }
        return webSnapshot(course, revisionId, version, bookKey);
    }

    private TextbookService.TocSnapshot webSnapshot(Course course, Long revisionId, int version, String bookKey) {
        TextbookWebRevision revision = effective(webMapper.findRevision(revisionId, course.getUserId()), course.getUserId());
        if (revision == null || revision.getTocEntryCount() < 1) {
            return null;
        }
        revisionId = revision.getRevisionId();
        List<TextbookExtractor.TocEntry> entries = webEntries(revision.getTocJson());
        if (entries.isEmpty()) {
            return null;
        }
        String key = bookKey != null ? bookKey : BookKey.of(revision.getTitle(), revision.getIsbn13(), revision.getEdition());
        String label = "웹 목차(" + siteName(revision.getSite()) + (revision.getFetchedAt() == null ? ""
                : " · " + revision.getFetchedAt().getMonthValue() + "/" + revision.getFetchedAt().getDayOfMonth() + " 조회")
                + " · " + coverageName(revision.getTocCoverage()) + ")";
        return new TextbookService.TocSnapshot(null, null, null, null, null, entries,
                new Basis("WEB", null, null, revisionId, version, key), label, "WEB",
                "SHARED".equals(revision.getCacheScopeKey()) ? revision.getUrl()
                        : com.jungwoo.project.memo.course.textbook.web.SafePageFetcher.masked(revision.getUrl()),
                revision.getFetchedAt() == null ? null : revision.getFetchedAt().format(AT),
                revision.getTocCoverage(),
                revision.getTocRawHash() != null ? revision.getTocRawHash()
                        : com.jungwoo.project.memo.course.textbook.web.WebEvidenceStore.rawHash(revision.getTocRaw()),
                unreadOf(revision.getTocJson()));
    }

    /**
     * 실제로 쓸 리비전. 옛 구조화 판이면 같은 페이지·같은 원문을 지금 판으로 다시 읽은 리비전을 따라간다(교재 칸·조회가 옛 리비전
     * 번호를 들고 있어도 새 구조를 본다). 범위(SHARED·그 사용자) 조건은 그대로다. 다시 읽은 것이 없으면 옛 것.
     */
    public TextbookWebRevision effective(TextbookWebRevision revision, Long userId) {
        if (!com.jungwoo.project.memo.course.textbook.web.WebEvidenceStore.needsRestructure(revision)) {
            return revision;
        }
        String hash = revision.getTocRawHash() != null ? revision.getTocRawHash()
                : com.jungwoo.project.memo.course.textbook.web.WebEvidenceStore.rawHash(revision.getTocRaw());
        TextbookWebRevision newer = webMapper.findRestructured(revision.getPageId(), hash,
                com.jungwoo.project.memo.course.textbook.web.WebTocStructurer.TOC_VERSION, userId);
        return newer != null ? newer : revision;
    }

    /** 목차 JSON에 남긴 "못 읽은 줄 수". 예전 JSON이면 lines - readLines. */
    int unreadOf(String tocJson) {
        if (tocJson == null) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(tocJson);
            if (root.has("unread")) {
                return Math.max(0, root.path("unread").asInt(0));
            }
            return Math.max(0, root.path("lines").asInt(0) - root.path("readLines").asInt(0));
        } catch (Exception e) {
            return 0;
        }
    }

    public List<TextbookExtractor.TocEntry> webEntries(String tocJson) {
        List<TextbookExtractor.TocEntry> out = new ArrayList<>();
        if (tocJson == null) {
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(tocJson);
            for (JsonNode e : root.path("entries")) {
                out.add(new TextbookExtractor.TocEntry(e.path("level").asInt(1), e.path("number").isNull() ? null
                        : e.path("number").asText(null), e.path("title").asText(""), e.path("page").isNumber()
                        ? e.path("page").asInt() : null, e.path("unit").asInt(0)));
            }
        } catch (Exception ignored) {
            // 읽지 못한 목차는 없는 것으로 본다(만들어 내지 않는다).
        }
        return out;
    }

    private LookupResult readResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LookupResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    static String siteName(String site) {
        return switch (site == null ? "" : site) {
            case "yes24" -> "예스24";
            case "aladin" -> "알라딘";
            case "kyobo" -> "교보문고";
            default -> "웹 페이지";
        };
    }

    static String coverageName(String coverage) {
        return switch (coverage == null ? "" : coverage) {
            case "PAGE_FULL" -> "페이지에 실린 목차 전체";
            case "PARTIAL" -> "목차 일부";
            default -> "범위 확인 못 함";
        };
    }

    private static String value(Map<String, TextbookExtractor.Field> book, String key) {
        TextbookExtractor.Field f = book.get(key);
        return f == null ? null : f.value();
    }
}
