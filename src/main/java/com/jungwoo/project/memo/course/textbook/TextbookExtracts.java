package com.jungwoo.project.memo.course.textbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 과목에 연결된 자료마다 규칙 추출(서지·목차·교재 단서)을 한 번 남기고 읽는다. 모델을 부르지 않는다.
 *
 * <p>(자료, 해시, 추출 판)마다 한 행이라 재분석·재시도·동시 조회가 중복을 만들지 않는다. 원문 단위가 아직 없으면(분석 전)
 * 파일에서 뽑은 전체 텍스트로 읽고, 그것도 없으면 "읽는 중"으로 센다 — 없는 목차를 "없음"으로 확정하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TextbookExtracts {

    private final MaterialLinkMapper linkMapper;
    private final CourseMaterialMapper materialMapper;
    private final MaterialTextUnitMapper unitMapper;
    private final MaterialTextUnitService unitService;
    private final MaterialTextbookExtractMapper extractMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param byId    ACTIVE이고 연결된 자료
     * @param types   연결 자료의 역할(SYLLABUS·TEXTBOOK_TOC·…)
     * @param pending 아직 원문을 읽지 못한 자료 수
     */
    public record Materials(List<MaterialTextbookExtract> extracts, Map<Long, CourseMaterial> byId,
                            Map<Long, String> types, int pending) {

        public MaterialTextbookExtract extractOf(Long materialId) {
            return extracts.stream().filter(e -> e.getMaterialId().equals(materialId)).findFirst().orElse(null);
        }
    }

    /** 이미 남은 추출만 읽는다(쓰지 않는다). 읽기 전용 트랜잭션의 화면 조회가 쓴다 — 아직 없는 자료는 "읽는 중"으로 센다. */
    public Materials loadExisting(Long userId, Long courseId) {
        return load(userId, courseId, false);
    }

    public Materials load(Long userId, Long courseId) {
        return load(userId, courseId, true);
    }

    private Materials load(Long userId, Long courseId, boolean write) {
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
        if (!write) {
            // 읽기 전용: 한 번에 읽고, 추출이 아직 없는 자료는 "읽는 중"으로 센다(자료마다 따로 묻지 않는다).
            List<MaterialTextbookExtract> existing = byId.isEmpty() ? List.of()
                    : extractMapper.findCurrent(new ArrayList<>(byId.keySet()), TextbookExtractor.VERSION, userId);
            java.util.Set<Long> have = new java.util.HashSet<>();
            existing.forEach(e -> have.add(e.getMaterialId()));
            for (CourseMaterial material : byId.values()) {
                if (!have.contains(material.getMaterialId())) {
                    pending++;
                }
            }
            return new Materials(existing, byId, types, pending);
        }
        for (CourseMaterial material : byId.values()) {
            if (material.getFileHash() == null) {
                pending++;
                continue;
            }
            if (extractMapper.find(material.getMaterialId(), material.getFileHash(), TextbookExtractor.VERSION, userId) != null) {
                continue;
            }
            List<TextbookExtractor.Unit> units = units(material);
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
                    .clueJson(clueJsonAtExtract(result, units))
                    .build());
        }
        List<MaterialTextbookExtract> extracts = byId.isEmpty() ? List.of()
                : extractMapper.findCurrent(new ArrayList<>(byId.keySet()), TextbookExtractor.VERSION, userId);
        return new Materials(extracts, byId, types, pending);
    }

    /**
     * 규칙이 책을 읽었으면 RULE, 못 읽었지만 교재 표 머리는 보이면 SIGNAL(조회 작업이 모델 보조로 한 번 더 본다), 둘 다 아니면 null.
     */
    private String clueJsonAtExtract(TextbookExtractor.Result result, List<TextbookExtractor.Unit> units) {
        if (!result.clues().isEmpty()) {
            return write(new ClueJson(ClueJson.RULE, result.clues()));
        }
        return SyllabusTextbookTable.findHeader(units) != null ? write(new ClueJson(ClueJson.SIGNAL, List.of())) : null;
    }

    public List<TextbookExtractor.Unit> units(CourseMaterial material) {
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

    // ===== 읽기 =====

    private static final Set<String> KNOWN = Set.copyOf(TextbookService.FIELDS);

    public Map<String, TextbookExtractor.Field> book(MaterialTextbookExtract extract) {
        if (extract == null || extract.getBookJson() == null) {
            return Map.of();
        }
        try {
            Map<String, TextbookExtractor.Field> raw = objectMapper.readValue(extract.getBookJson(),
                    new TypeReference<LinkedHashMap<String, TextbookExtractor.Field>>() {
                    });
            raw.keySet().retainAll(KNOWN);
            return raw;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record TocJson(List<TextbookExtractor.TocEntry> entries, Integer fromUnit, Integer toUnit) {
    }

    public TocJson toc(MaterialTextbookExtract extract) {
        if (extract == null || extract.getTocJson() == null) {
            return new TocJson(List.of(), null, null);
        }
        try {
            TocJson toc = objectMapper.readValue(extract.getTocJson(), TocJson.class);
            return new TocJson(toc.entries() == null ? List.of() : toc.entries(), toc.fromUnit(), toc.toUnit());
        } catch (Exception e) {
            return new TocJson(List.of(), null, null);
        }
    }

    /**
     * @param source RULE(규칙) · SIGNAL(교재 표 머리는 있으나 규칙이 못 읽음 — 모델 보조 대기) ·
     *               MODEL(모델이 줄을 짚고 서버가 원문에서 자른 값) · NONE(모델 보조로도 못 찾음)
     */
    public record ClueJson(String source, List<TextbookExtractor.BookClue> clues) {
        public static final String RULE = "RULE";
        public static final String SIGNAL = "SIGNAL";
        public static final String MODEL = "MODEL";
        public static final String NONE = "NONE";
    }

    public ClueJson clues(MaterialTextbookExtract extract) {
        if (extract == null || extract.getClueJson() == null) {
            return null;
        }
        try {
            ClueJson c = objectMapper.readValue(extract.getClueJson(), ClueJson.class);
            return new ClueJson(c.source(), c.clues() == null ? List.of() : c.clues());
        } catch (Exception e) {
            return null;
        }
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
