package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 이번 생성 회차에 모델 입력으로 실제 제공한 원본 하나.
 *
 * <p>★ <b>제공한 값 자체를 보존한다.</b> type/id/version만 남기면 원본이 바뀐 뒤에는 "그때
 * 무엇을 보고 만든 계획인가"를 복원할 수 없다(handoff §3.1). 그래서 {@link #providedValue}와
 * 프롬프트에 실제로 들어간 {@link #promptLine}을 함께 남긴다 — 둘이 대응하지 않으면
 * 스냅샷이 거짓이다.
 *
 * <p>★ 원본 전체를 무조건 복사하지 않는다. 먼저 입력에 필요한 필드만 고르고, 고른 그 값을
 * 그대로 보존한다. 스냅샷을 만들려고 모델에 개인정보를 더 주지 않는다.
 *
 * <p>{@link #sourceVersion}/{@link #sourceUpdatedAt}은 있으면 남기는 메타데이터일 뿐,
 * 스냅샷을 대체하지 않는다.
 *
 * @param refId          이번 회차 안에서만 유효한 인용 번호("s1"). 모델은 이 값만 인용한다
 * @param sourceId       그 원본 행의 id. 가리킬 행이 없으면 null(TURN_INPUT 등)
 * @param promptLine     최종 프롬프트에 그대로 들어간 한 줄. 길이 제한·요약이 이미 적용된 값이다
 * @param parentSourceId 생성 당시 구조에서 이 원본의 부모 행 id(TOPIC이면 parent_topic_id).
 *                       화면이 부모·자식을 묶을 때 쓴다. 관계가 없거나 1판이면 null.
 *                       제목 유사성으로 계층을 만들지 않기 위해 당시 구조를 그대로 남긴다
 * @param material       이 줄이 나온 자료 파일. 서버가 원문 탐색을 위해 붙인 메타데이터이며
 *                       모델에 준 값이 아니다. 자료 연결이 없거나 1판이면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvidedSource(
        String refId,
        ProvenanceSourceType sourceType,
        Long sourceId,
        Long sourceVersion,
        LocalDateTime sourceUpdatedAt,
        ProvenanceRepresentation representation,
        Map<String, Object> providedValue,
        String promptLine,
        Long parentSourceId,
        ProvidedMaterial material
) {

    /** 1판 모양(구조·자료 정보 없음)으로 만든다. 기존 호출부와 테스트가 쓴다. */
    public ProvidedSource(String refId, ProvenanceSourceType sourceType, Long sourceId, Long sourceVersion,
                          LocalDateTime sourceUpdatedAt, ProvenanceRepresentation representation,
                          Map<String, Object> providedValue, String promptLine) {
        this(refId, sourceType, sourceId, sourceVersion, sourceUpdatedAt, representation, providedValue,
                promptLine, null, null);
    }
}
