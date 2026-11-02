package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.BookKey;
import com.jungwoo.project.memo.course.textbook.MaterialTextbookExtract;
import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookExtracts;
import com.jungwoo.project.memo.material.domain.CourseMaterial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지금 무엇을 찾아야 하는가(조회 대상)와 그 근거 열쇠(basis_key). 모델·외부 호출 없음.
 *
 * <ul>
 *   <li>교재 칸에 제목이나 ISBN이 있으면 그것 — 사용자가 "실제로는 B책"이라고 고친 교재가 강의계획서의 A보다 앞선다.</li>
 *   <li>교재 칸이 비어 있으면 강의계획서 등의 주교재 단서. 서로 다른 주교재가 여럿이면 고르지 않고 CONFLICT.</li>
 *   <li>규칙이 책을 못 읽었지만 교재 표 머리가 보이면 모델 보조 단서가 필요하다(NEEDS_CLUE).</li>
 *   <li>사용자가 준 링크(USER_LINK)는 교재 판이 그대로인 동안 그것이 조회 대상이다.</li>
 * </ul>
 * basis_key는 모든 경우에 과목·교재 판·단서 종류를 넣고, 종류별 입력(교재 칸 값 / 단서 자료·해시·추출 판·값 / 링크)을 더한다.
 * 결과를 저장·사용하는 순간 다시 계산해 다르면 그 결과는 지난 것이다.
 */
public final class TextbookLookupPlanner {

    private TextbookLookupPlanner() {
    }

    public enum Kind {
        /** 찾을 단서가 없다. */
        NONE,
        /** 사용자가 외부 조회를 껐다. */
        DISABLED,
        /** 서로 다른 주교재 단서가 여럿이다. */
        CONFLICT,
        /** 교재 표 머리는 있는데 규칙이 책을 못 읽었다 — 모델 보조가 먼저다. */
        NEEDS_CLUE,
        QUERY
    }

    /** 강의계획서 등에서 읽은 교재 단서와 그 자료. */
    public record SourcedClue(TextbookExtractor.BookClue clue, Long materialId, String filename, String fileHash,
                              String source) {
    }

    public record Desired(Kind kind, String origin, BookMatcher.Clue clue, Long clueMaterialId, String clueFileHash,
                          Integer clueExtractorVersion, String linkUrl, List<SourcedClue> syllabusClues,
                          String basisKey, String queryKey, int textbookVersion) {
    }

    public static Desired plan(Course course, TextbookExtracts.Materials materials, TextbookExtracts extracts,
                               TextbookLookup latest) {
        int version = course.getTextbookVersion() == null ? 0 : course.getTextbookVersion();
        List<SourcedClue> syllabus = syllabusClues(materials, extracts);
        if (Boolean.FALSE.equals(course.getTextbookWebLookupEnabled())) {
            return new Desired(Kind.DISABLED, null, null, null, null, null, null, syllabus,
                    hash(course.getCourseId() + "|" + version + "|DISABLED"), null, version);
        }
        // 사용자가 준 링크는 교재 판이 그대로인 동안 유지한다.
        if (latest != null && "USER_LINK".equals(latest.getClueOrigin()) && latest.getTextbookVersion() != null
                && latest.getTextbookVersion() == version && !TextbookLookup.SUPERSEDED.equals(latest.getStatus())
                && !TextbookLookup.CANCELLED.equals(latest.getStatus())) {
            return new Desired(Kind.QUERY, "USER_LINK", currentClue(course), null, null, null,
                    linkOf(latest), syllabus, latest.getBasisKey(), latest.getQueryKey(), version);
        }
        if (BookKey.of(course) != null) {
            BookMatcher.Clue clue = currentClue(course);
            String origin = blank(course.getTextbookTitle()) ? "ISBN" : "CURRENT_TEXTBOOK";
            String q = clueKey(clue);
            return new Desired(Kind.QUERY, origin, clue, null, null, null, null, syllabus,
                    hash(course.getCourseId() + "|" + version + "|" + origin + "|" + q), hash("q|" + q), version);
        }
        List<SourcedClue> mains = mains(syllabus);
        if (mains.size() > 1) {
            StringBuilder sb = new StringBuilder();
            mains.forEach(c -> sb.append(c.materialId()).append(':').append(c.fileHash()).append(':')
                    .append(BookMatcher.titleKey(c.clue().title())).append(';'));
            return new Desired(Kind.CONFLICT, "SYLLABUS", null, null, null, null, null, syllabus,
                    hash(course.getCourseId() + "|" + version + "|SYLLABUS|CONFLICT|" + sb), null, version);
        }
        if (mains.size() == 1) {
            SourcedClue c = mains.get(0);
            BookMatcher.Clue clue = new BookMatcher.Clue(c.clue().title(), c.clue().author(), c.clue().publisher(),
                    c.clue().isbn(), c.clue().edition());
            String q = clueKey(clue);
            return new Desired(Kind.QUERY, "SYLLABUS", clue, c.materialId(), c.fileHash(), TextbookExtractor.VERSION,
                    null, syllabus, hash(course.getCourseId() + "|" + version + "|SYLLABUS|" + c.materialId() + "|"
                    + c.fileHash() + "|" + TextbookExtractor.VERSION + "|" + q), hash("q|" + q), version);
        }
        // 규칙이 못 읽었지만 교재 표가 보이는 자료 — 모델 보조로 한 번 더 본다.
        for (MaterialTextbookExtract e : materials.extracts()) {
            TextbookExtracts.ClueJson cj = extracts.clues(e);
            CourseMaterial material = materials.byId().get(e.getMaterialId());
            if (cj != null && TextbookExtracts.ClueJson.SIGNAL.equals(cj.source()) && material != null) {
                return new Desired(Kind.NEEDS_CLUE, "SYLLABUS", null, e.getMaterialId(), e.getFileHash(),
                        TextbookExtractor.VERSION, null, syllabus,
                        hash(course.getCourseId() + "|" + version + "|SYLLABUS|NEEDS_CLUE|" + e.getMaterialId() + "|"
                                + e.getFileHash() + "|" + TextbookExtractor.VERSION),
                        hash("clue|" + e.getMaterialId() + "|" + e.getFileHash()), version);
            }
        }
        return new Desired(Kind.NONE, null, null, null, null, null, null, syllabus,
                hash(course.getCourseId() + "|" + version + "|NONE"), null, version);
    }

    /** 사용자 링크 조회의 basis. */
    public static String linkBasis(Course course, String normalizedUrl) {
        int version = course.getTextbookVersion() == null ? 0 : course.getTextbookVersion();
        return hash(course.getCourseId() + "|" + version + "|USER_LINK|" + normalizedUrl);
    }

    public static String linkQueryKey(String normalizedUrl) {
        return hash("link|" + normalizedUrl);
    }

    /** 자료마다 읽은 단서(RULE·MODEL). SYLLABUS 역할 자료를 앞에 둔다. */
    public static List<SourcedClue> syllabusClues(TextbookExtracts.Materials materials, TextbookExtracts extracts) {
        List<SourcedClue> first = new ArrayList<>();
        List<SourcedClue> rest = new ArrayList<>();
        for (MaterialTextbookExtract e : materials.extracts()) {
            TextbookExtracts.ClueJson cj = extracts.clues(e);
            if (cj == null || cj.clues().isEmpty()) {
                continue;
            }
            CourseMaterial material = materials.byId().get(e.getMaterialId());
            boolean syllabus = "SYLLABUS".equals(materials.types().get(e.getMaterialId()));
            for (TextbookExtractor.BookClue clue : cj.clues()) {
                SourcedClue sc = new SourcedClue(clue, e.getMaterialId(),
                        material == null ? null : material.getOriginalFilename(), e.getFileHash(), cj.source());
                (syllabus ? first : rest).add(sc);
            }
        }
        first.addAll(rest);
        return first;
    }

    /** 주교재 단서(서로 다른 책만). 주교재 표시가 없으면 참고 도서가 아닌 단서. */
    static List<SourcedClue> mains(List<SourcedClue> all) {
        List<SourcedClue> picked = new ArrayList<>(all.stream().filter(c -> "MAIN".equals(c.clue().role())).toList());
        if (picked.isEmpty()) {
            picked = new ArrayList<>(all.stream().filter(c -> !"REFERENCE".equals(c.clue().role())
                    && !"SUPPLEMENT".equals(c.clue().role())).toList());
        }
        Map<String, SourcedClue> distinct = new LinkedHashMap<>();
        for (SourcedClue c : picked) {
            String key = BookKey.of(c.clue().title(), c.clue().isbn(), null);
            if (key != null) {
                distinct.putIfAbsent(key, c);
            }
        }
        return new ArrayList<>(distinct.values());
    }

    public static BookMatcher.Clue currentClue(Course course) {
        return new BookMatcher.Clue(trim(course.getTextbookTitle()), trim(course.getTextbookAuthor()),
                trim(course.getTextbookPublisher()), trim(course.getTextbookIsbn()), trim(course.getTextbookEdition()));
    }

    static String clueKey(BookMatcher.Clue clue) {
        return BookMatcher.titleKey(clue.title()) + "|" + norm(clue.author()) + "|" + norm(clue.publisher()) + "|"
                + norm(clue.isbn()).replace("-", "") + "|" + norm(clue.edition());
    }

    private static String linkOf(TextbookLookup latest) {
        if (latest.getQueryJson() == null) {
            return null;
        }
        int i = latest.getQueryJson().indexOf("\"link\":\"");
        if (i < 0) {
            return null;
        }
        int end = latest.getQueryJson().indexOf('"', i + 8);
        return end < 0 ? null : latest.getQueryJson().substring(i + 8, end);
    }

    private static String norm(String v) {
        return v == null ? "" : v.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String trim(String v) {
        return blank(v) ? null : v.trim();
    }

    public static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
