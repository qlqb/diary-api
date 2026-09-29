package com.jungwoo.project.memo.course.textbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 프로젝트의 교재: 어느 책인가(서지)와 그 책이 다루는 범위(목차).
 *
 * <p>자동 분석 흐름 안에서 돈다 — 옛 [구조 분석] 버튼을 찾지 않아도, 프로젝트를 열면 연결된 자료의 원문 단위에서
 * 교재 단서를 규칙으로 읽어 {@code material_textbook_extracts}에 한 번 남기고, 지금 교재 칸과 비교해 보여 준다.
 *
 * <ul>
 *   <li><b>조용히 덮지 않는다.</b> 자료에서 찾은 값은 후보다. 사용자가 고른 칸만 적용되고, 적용하려는 순간 교재 칸이
 *       화면이 본 값과 다르면(다른 탭·직접 수정) 409로 멈춘다. 후보가 그 사이 바뀌어도 409다.</li>
 *   <li><b>목차를 상상하지 않는다.</b> 책 이름만 있으면 "목차 미확보"와 다음 행동만 알리고, 계획·학습은 그대로 된다.</li>
 *   <li><b>같은 파일은 한 번만.</b> (자료, 해시, 추출 판)마다 한 행이라 재분석·재시도·동시 조회가 중복을 만들지 않는다.</li>
 *   <li>외부 도서 검색은 쓰지 않는다. 판 정보는 원문에 적힌 것만이다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextbookService {

    static final List<String> FIELDS = List.of("title", "author", "publisher", "isbn", "edition");

    private final CourseMapper courseMapper;
    private final CourseTopicMapper topicMapper;
    private final MaterialLinkMapper linkMapper;
    private final CourseMaterialMapper materialMapper;
    private final MaterialTextUnitMapper unitMapper;
    private final MaterialTextUnitService unitService;
    private final MaterialTextbookExtractMapper extractMapper;
    private final ObjectMapper objectMapper;

    // ===== 조회 =====

    @Transactional
    public TextbookReview review(Long userId, Long courseId) {
        Course course = owned(userId, courseId);
        Materials materials = ensureExtracts(userId, courseId);
        Map<String, String> current = currentValues(course);

        List<TextbookReview.Candidate> candidates = new ArrayList<>();
        for (MaterialTextbookExtract extract : materials.extracts()) {
            Map<String, TextbookExtractor.Field> book = readBook(extract.getBookJson());
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

        MaterialTextbookExtract tocSource = bestToc(materials);
        TextbookReview.Toc toc;
        if (tocSource == null) {
            toc = new TextbookReview.Toc("NOT_FOUND", null, null, 0, null, null, List.of());
        } else {
            TocJson json = readToc(tocSource.getTocJson());
            CourseMaterial material = materials.byId().get(tocSource.getMaterialId());
            toc = new TextbookReview.Toc("FOUND", tocSource.getMaterialId(),
                    material == null ? null : material.getOriginalFilename(), tocSource.getTocEntryCount(),
                    json.fromUnit(), json.toUnit(), json.entries());
        }

        boolean hasBookIdentity = current.get("title") != null || current.get("isbn") != null
                || candidates.stream().anyMatch(c -> c.fields().stream()
                .anyMatch(f -> f.field().equals("title") || f.field().equals("isbn")));
        String state = "FOUND".equals(toc.status()) ? "TOC_FOUND" : hasBookIdentity ? "TITLE_ONLY" : "NONE";
        int topics = topicMapper.findActiveByCourseIdAndUserId(courseId, userId).size();
        return new TextbookReview(courseId, TextbookReview.Current.of(course), candidates, toc, state,
                nextAction(state, topics, materials.pending()), topics, materials.pending());
    }

    private static String nextAction(String state, int topics, int pending) {
        if ("TOC_FOUND".equals(state)) {
            return topics == 0
                    ? "목차로 학습 구조의 첫 골격을 만들 수 있어요. [이 프로젝트 자료 정리]에서 골격을 검토하고 적용하세요."
                    : "목차와 지금 학습 구조를 비교해 필요한 변경만 제안받을 수 있어요. [이 프로젝트 자료 정리]를 눌러 주세요.";
        }
        String waiting = pending > 0 ? " 아직 읽는 중인 자료 " + pending + "개는 끝나면 다시 확인해요." : "";
        if ("TITLE_ONLY".equals(state)) {
            return "교재 목차를 아직 확보하지 못했어요. 목차 쪽(사진·PDF)이나 본문을 올리면 범위를 확인할 수 있어요. "
                    + "지금 자료로도 계획과 학습은 그대로 진행할 수 있어요." + waiting;
        }
        return "교재 정보가 없어요. 교재를 쓰는 과목이면 이름을 적거나 목차·판권 쪽을 올려 주세요. 없어도 계획·학습은 할 수 있어요." + waiting;
    }

    // ===== 적용 =====

    /**
     * 자료에서 찾은 값 중 사용자가 고른 칸만 교재 칸에 넣는다.
     *
     * @param expected 화면이 본 지금 교재 칸 값(고른 칸만). 지금 값과 다르면 409 — 그 사이 사용자가 고친 값을 덮지 않는다
     * @param values   화면이 본 후보 값(고른 칸만). 지금 추출한 후보와 다르면 409
     */
    @Transactional
    public TextbookReview apply(Long userId, Long courseId, Long materialId, Map<String, String> values,
                                Map<String, String> expected) {
        if (materialId == null || values == null || values.isEmpty()
                || !FIELDS.containsAll(values.keySet())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        Map<String, String> current = currentValues(course);
        for (String key : values.keySet()) {
            String seen = expected == null ? null : blankToNull(expected.get(key));
            if (!Objects.equals(seen, current.get(key))) {
                throw new ConflictException(ErrorCode.TEXTBOOK_INFO_CHANGED);
            }
        }
        Materials materials = ensureExtracts(userId, courseId);
        MaterialTextbookExtract extract = materials.extracts().stream()
                .filter(e -> e.getMaterialId().equals(materialId)).findFirst().orElse(null);
        if (extract == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        Map<String, TextbookExtractor.Field> book = readBook(extract.getBookJson());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            TextbookExtractor.Field found = book.get(entry.getKey());
            if (found == null || !found.value().equals(entry.getValue())) {
                throw new ConflictException(ErrorCode.TEXTBOOK_CANDIDATE_CHANGED);
            }
        }
        courseMapper.applyTextbookFromMaterial(courseId, userId, materialId,
                cut(values.get("title"), 300), cut(values.get("author"), 200), cut(values.get("publisher"), 200),
                cut(values.get("isbn"), 50), cut(values.get("edition"), 100));
        log.info("교재 정보 적용: courseId={}, materialId={}, fields={}", courseId, materialId, values.keySet());
        return review(userId, courseId);
    }

    // ===== 정리(tidy)가 쓰는 목차 =====

    /** 이 프로젝트에서 확보한 교재 목차. 없으면 null — 목차를 만들어 내지 않는다. */
    @Transactional
    public TocSnapshot tocOf(Long userId, Long courseId) {
        Materials materials = ensureExtracts(userId, courseId);
        MaterialTextbookExtract best = bestToc(materials);
        if (best == null) {
            return null;
        }
        TocJson json = readToc(best.getTocJson());
        CourseMaterial material = materials.byId().get(best.getMaterialId());
        return new TocSnapshot(best.getMaterialId(), material == null ? null : material.getOriginalFilename(),
                best.getFileHash(), json.fromUnit(), json.toUnit(), json.entries());
    }

    public record TocSnapshot(Long materialId, String filename, String fileHash, Integer fromUnit, Integer toUnit,
                              List<TextbookExtractor.TocEntry> entries) {
    }

    // ===== 추출 =====

    record Materials(List<MaterialTextbookExtract> extracts, Map<Long, CourseMaterial> byId,
                     Map<Long, String> types, int pending) {
    }

    /**
     * 연결된 자료마다 지금 파일의 추출이 있게 한다. 원문 단위가 아직 없으면(분석 전) 파일에서 뽑은 전체 텍스트로 읽고,
     * 그것도 없으면 건너뛰고 "읽는 중"으로 센다 — 없는 목차를 "없음"으로 확정하지 않는다.
     */
    private Materials ensureExtracts(Long userId, Long courseId) {
        List<MaterialLink> links = linkMapper.findByCourseIdAndUserId(courseId, userId);
        Map<Long, String> types = new LinkedHashMap<>();
        for (MaterialLink link : links) {
            types.put(link.getMaterialId(), link.getMaterialType() == null ? null : link.getMaterialType().name());
        }
        if (types.isEmpty()) {
            return new Materials(List.of(), Map.of(), types, 0);
        }
        Map<Long, CourseMaterial> byId = new LinkedHashMap<>();
        for (CourseMaterial material : materialMapper.findByIdsAndUserIdIncludingDeleted(new ArrayList<>(types.keySet()), userId)) {
            if (material.getStatus() == MaterialStatus.ACTIVE) {
                byId.put(material.getMaterialId(), material);
            }
        }
        int pending = 0;
        for (CourseMaterial material : byId.values()) {
            if (material.getFileHash() == null) {
                pending++;
                continue;
            }
            if (extractMapper.find(material.getMaterialId(), material.getFileHash(), TextbookExtractor.VERSION, userId) != null) {
                continue;
            }
            List<TextbookExtractor.Unit> units = unitsOf(material);
            if (units.isEmpty()) {
                pending++;
                continue;
            }
            TextbookExtractor.Result result = TextbookExtractor.extract(units);
            extractMapper.insertIgnore(MaterialTextbookExtract.builder()
                    .userId(userId).materialId(material.getMaterialId()).fileHash(material.getFileHash())
                    .extractorVersion(TextbookExtractor.VERSION)
                    .bookJson(result.book().isEmpty() ? null : write(result.book()))
                    .tocJson(result.hasToc() ? write(new TocJson(result.toc(), result.tocFromUnit(), result.tocToUnit())) : null)
                    .tocEntryCount(result.toc().size())
                    .build());
        }
        List<MaterialTextbookExtract> extracts = byId.isEmpty() ? List.of()
                : extractMapper.findCurrent(new ArrayList<>(byId.keySet()), TextbookExtractor.VERSION, userId);
        return new Materials(extracts, byId, types, pending);
    }

    private List<TextbookExtractor.Unit> unitsOf(CourseMaterial material) {
        List<MaterialTextUnit> units = unitMapper.findByMaterialIdAndHash(material.getMaterialId(), material.getFileHash());
        if (units.isEmpty() && material.getExtractedText() != null) {
            // 분석 전 자료: 저장하지 않고 이 자리에서만 나눠 읽는다(단위 저장은 분석의 일이다).
            units = unitService.blocksFromText(material.getExtractedText(), material.getUserId(), material.getMaterialId(),
                    material.getFileHash());
        }
        List<TextbookExtractor.Unit> out = new ArrayList<>();
        for (MaterialTextUnit unit : units) {
            out.add(new TextbookExtractor.Unit(unit.getUnitNo() == null ? out.size() + 1 : unit.getUnitNo(), unit.getText()));
        }
        return out;
    }

    /** 목차가 가장 긴 자료. 같으면 "교재 목차"로 연결한 자료를 앞에 둔다. */
    private MaterialTextbookExtract bestToc(Materials materials) {
        return materials.extracts().stream()
                .filter(e -> e.getTocEntryCount() >= 3 && e.getTocJson() != null)
                .max(Comparator.comparingInt((MaterialTextbookExtract e) -> e.getTocEntryCount())
                        .thenComparing(e -> MaterialType.TEXTBOOK_TOC.name().equals(materials.types().get(e.getMaterialId()))))
                .orElse(null);
    }

    // ===== 도움 =====

    private Course owned(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
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

    private static final Set<String> KNOWN = Set.copyOf(FIELDS);

    private Map<String, TextbookExtractor.Field> readBook(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, TextbookExtractor.Field> raw = objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, TextbookExtractor.Field>>() {
                    });
            raw.keySet().retainAll(KNOWN);
            return raw;
        } catch (Exception e) {
            return Map.of();
        }
    }

    record TocJson(List<TextbookExtractor.TocEntry> entries, Integer fromUnit, Integer toUnit) {
    }

    private TocJson readToc(String json) {
        try {
            TocJson toc = objectMapper.readValue(json, TocJson.class);
            return new TocJson(toc.entries() == null ? List.of() : toc.entries(), toc.fromUnit(), toc.toUnit());
        } catch (Exception e) {
            return new TocJson(List.of(), null, null);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        String v = blankToNull(value);
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }
}
