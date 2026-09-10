package com.jungwoo.project.memo.plan.provenance;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 프롬프트를 만들면서 <b>같은 자리에서</b> 스냅샷을 모은다. 생성 1회당 하나 만들고 버린다.
 *
 * <p>★ 이 클래스의 존재 이유는 하나다: <b>DB를 다시 조회해 스냅샷을 만들지 않기 위해서.</b>
 * 재조회로 만들면 그 사이 바뀐 값이 "그때 준 값"으로 저장되고, 스냅샷은 조용히 거짓이 된다.
 * 그래서 프롬프트 한 줄을 쓰는 코드가 {@link #mark}를 불러 줄을 받아 가고, 그 반환값이
 * 그대로 프롬프트에 들어간다 — 준 것과 남긴 것이 갈라질 자리가 없다.
 *
 * <p>★ 잘려서 프롬프트에 안 들어간 줄은 {@link #mark}를 부르지 않으므로 목록에도 없다.
 * 길이 제한은 항상 "한 줄 통째로" 단위로 처리해 ref와 값의 대응이 깨지지 않게 한다.
 *
 * <p>스레드 안전하지 않다. 한 요청 안에서만 쓴다.
 */
@Slf4j
public class ProvenanceCollector {

    /**
     * 한 회차에 담을 출처 줄 수의 안전 상한.
     *
     * <p>프롬프트 자체가 이미 블록마다 상한을 갖고 있어(과목당 학습 항목 30줄, 일정 40줄 등)
     * 정상 흐름에서는 닿지 않는다. 닿았다는 것은 프롬프트가 예상보다 커졌다는 뜻이므로
     * 조용히 자르지 않고 경고를 남긴다 — 스냅샷이 소리 없이 잘리면 그 회차의 대응 검증이
     * 통째로 무의미해진다(handoff §8).
     */
    private static final int MAX_SOURCES = 400;

    private final String generationId;
    private final LocalDateTime capturedAt;
    private final String timezone;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String generator;
    private final String modelName;

    private final List<ProvidedSource> sources = new ArrayList<>();
    private final List<ServerCalculation> calculations = new ArrayList<>();
    private int nextRef = 1;
    private boolean overflowWarned;

    public ProvenanceCollector(LocalDateTime capturedAt, String timezone, LocalDate startDate,
                               LocalDate endDate, String generator, String modelName) {
        this.generationId = "gen-" + UUID.randomUUID();
        this.capturedAt = capturedAt;
        this.timezone = timezone;
        this.startDate = startDate;
        this.endDate = endDate;
        this.generator = generator;
        this.modelName = modelName;
    }

    public String generationId() {
        return generationId;
    }

    /**
     * 출처 한 줄을 등록하고 <b>프롬프트에 넣을 문장</b>을 돌려준다.
     *
     * <p>호출부는 반환값을 그대로 프롬프트에 쓴다. 반환값을 다시 가공하면 저장된
     * promptLine과 실제 입력이 달라진다.
     *
     * @param text 인용 번호가 붙기 전의 줄. 이미 요약·길이 제한이 적용된 최종 형태여야 한다
     * @return "원래 줄 [s3]" 형태. 번호를 줄 끝에 두는 것은 목록 들여쓰기를 깨지 않기
     *         위해서다. 상한을 넘으면 번호 없이 원래 줄을 그대로 돌려준다
     */
    public Marked mark(ProvenanceSourceType type, Long sourceId, Long sourceVersion,
                       LocalDateTime sourceUpdatedAt, ProvenanceRepresentation representation,
                       Map<String, Object> providedValue, String text) {
        if (sources.size() >= MAX_SOURCES) {
            if (!overflowWarned) {
                overflowWarned = true;
                log.warn("계획 생성 출처 기록이 상한({})에 닿았다 — 이후 줄은 인용 번호 없이 나간다. "
                        + "프롬프트 블록 상한을 점검해야 한다. generationId={}", MAX_SOURCES, generationId);
            }
            return new Marked(null, text);
        }
        String refId = "s" + nextRef++;
        String line = text + " [" + refId + "]";
        sources.add(new ProvidedSource(refId, type, sourceId, sourceVersion, sourceUpdatedAt,
                representation, providedValue == null ? Map.of() : providedValue, line));
        return new Marked(refId, line);
    }

    /** 값 맵을 만들기 위한 짧은 도우미. 순서를 유지한다. */
    public static Map<String, Object> value(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    /** 서버 계산 하나를 등록하고 그 식별자를 돌려준다. */
    public String calculation(ServerCalculation.ServerCalculationKind kind, boolean providedToModel,
                              List<String> inputRefIds, ServerCalculation.InputLineage lineage,
                              String lineageNote, Map<String, Object> result) {
        String id = "calc-" + (calculations.size() + 1);
        calculations.add(new ServerCalculation(id, kind, providedToModel,
                inputRefIds == null ? List.of() : List.copyOf(inputRefIds),
                lineage, lineageNote, result == null ? Map.of() : result));
        return id;
    }

    /** 등록된 서버 계산의 식별자. 등록 순서를 따른다. */
    public List<String> calculationIds() {
        List<String> ids = new ArrayList<>();
        for (ServerCalculation calculation : calculations) {
            ids.add(calculation.calculationId());
        }
        return ids;
    }

    /** 지금까지 등록된 refId 전부. 계산의 입력 계보를 만들 때 쓴다. */
    public List<String> refIdsOfType(Set<ProvenanceSourceType> types) {
        List<String> ids = new ArrayList<>();
        for (ProvidedSource source : sources) {
            if (types.contains(source.sourceType())) {
                ids.add(source.refId());
            }
        }
        return ids;
    }

    public PlanProvenance build() {
        return new PlanProvenance(PlanProvenance.SCHEMA_VERSION, generationId, capturedAt, timezone,
                startDate, endDate, generator, modelName, List.copyOf(sources), List.copyOf(calculations));
    }

    /**
     * 등록 결과. refId는 상한에 걸린 줄에서만 null이다.
     *
     * @param text 프롬프트에 넣을 최종 문장
     */
    public record Marked(String refId, String text) {
    }
}
