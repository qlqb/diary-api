package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 한 번의 계획 생성에서 <b>모델에 무엇을 줬는가</b>. 서버가 만들고, 만든 뒤에는 바뀌지 않는다.
 *
 * <p>plan_strategy_json("왜 그렇게 하기로 했는가") 옆의 "무엇을 보고 그렇게 봤는가"다. 둘은
 * 같은 것이 아니다 — 판단은 서버·모델이 내린 결론이고 이쪽은 그 결론 이전에 입력으로 준
 * 사실들이다.
 *
 * <p>★ <b>모델도 클라이언트도 이 값을 쓰지 못한다.</b> 모델 응답에는 항목별 refId와 추정만
 * 들어 있고, 저장 API는 이 필드를 받지 않는다. 그래서 "출처가 있다"가 곧 "모델이 그렇게
 * 주장했다"가 되지 않는다.
 *
 * <p>★ 제안 하나에 한 번만 저장하고 항목은 {@link PlanItemEvidence#generationId()}로 참조한다.
 * 재생성하면 새 제안·새 회차가 만들어지고 기존 스냅샷은 그대로 남는다.
 *
 * @param schemaVersion 이 구조의 판. 늘어난 필드를 모르는 과거 코드가 통째로 실패하지
 *                      않도록 두는 값이다. 1판은 원본 줄과 서버 계산만, 2판(2026-09-11)은
 *                      원본 줄에 당시 구조(parentSourceId)와 자료 파일(material)을 더했다.
 *                      1판 JSON은 그대로 읽히고 늘어난 필드는 null이다
 * @param generationId  이 생성 회차의 식별자. 항목의 근거가 어느 회차를 가리키는지 판별한다
 * @param capturedAt    스냅샷을 만든 시각. 프롬프트를 만든 시각과 같다
 * @param timezone      날짜 해석에 쓴 시간대. 값 자체는 기존 API 계약대로 오프셋 없는 시각이다
 * @param generator     이 회차를 만든 경로(AI / V0 / JUDGMENT / V1). 같은 화면에 서로 다른
 *                      경로의 초안이 섞여 보일 수 있어 남긴다
 * @param modelName     실제로 부른 모델. 모델을 부르지 않은 경로(V0)는 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanProvenance(
        int schemaVersion,
        String generationId,
        LocalDateTime capturedAt,
        String timezone,
        LocalDate startDate,
        LocalDate endDate,
        String generator,
        String modelName,
        List<ProvidedSource> providedSources,
        List<ServerCalculation> serverCalculations
) {

    public static final int SCHEMA_VERSION = 2;

    /** 이번 회차에 실제로 제공한 refId 집합. 항목별 인용 검증이 이 집합만 인정한다. */
    public Set<String> refIds() {
        if (providedSources == null) {
            return Set.of();
        }
        return providedSources.stream()
                .map(ProvidedSource::refId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
