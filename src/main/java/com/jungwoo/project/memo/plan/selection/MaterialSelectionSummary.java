package com.jungwoo.project.memo.plan.selection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이번 생성에서 모델이 무엇을 골랐고, 서버가 무엇을 읽어 계획 호출에 넣었는지. 응답(화면)과 출처 스냅샷(서버 계산
 * MATERIAL_SELECTION)에 같은 모양으로 남는다.
 *
 * <p>"전체 자료를 검토했다"고 말하지 않기 위한 값들이 함께 있다: 후보 수와 줄로 보여 준 수, 끝까지 보여 주지 못한 묶음
 * ({@code unreviewed}), 고른 구간마다 원문을 얼마나 읽었는지({@code outcome}, {@code retrievedRange}).
 *
 * @param status           SELECTED / EMPTY(정상적인 빈 선택) / NO_CANDIDATES(후보 없음 — 선택 호출을 하지 않았다)
 * @param mode             NONE / FULL / FOLDED_GROUPS / FOLDED_COURSES
 * @param selectionCalls   이번 생성의 선택 호출 수(0~2). 계획 호출은 따로 1회
 * @param perSectionChars  계획 호출에 구간 하나당 실은 원문 글자 상한(예산에 맞춰 줄였을 수 있다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterialSelectionSummary(
        String status,
        String mode,
        int selectionCalls,
        boolean expanded,
        int candidateTotal,
        int candidateShown,
        List<SectionPick> sections,
        List<TopicPick> topics,
        List<Unreviewed> unreviewed,
        boolean insufficientEvidence,
        String note,
        int unknownIds,
        int overLimit,
        List<RequestedMaterialView> requestedMaterials,
        List<AmbiguityView> ambiguities,
        List<ExcludedTopicView> excludedTopics,
        List<Integer> selectionInputTokens,
        Integer planInputTokens,
        int perSectionChars
) {

    /**
     * @param outcome        FULL / PARTIAL / EXCERPT_ONLY(원문 단위 없음) / DROPPED_DELETED / DROPPED_CHANGED /
     *                       DROPPED_SCOPE / NOT_RETRIEVED_BUDGET
     * @param retrievedRange 실제로 읽어 넣은 범위 문장. 떨어졌으면 null
     * @param topicId        그 구간이 실린 학습 항목(없으면 null)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SectionPick(Long sectionId, Long materialId, Long courseId, String title, String locator,
                              String filename, String reason, String outcome, String retrievedRange,
                              Integer retrievedChars, Long topicId, String refId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicPick(Long topicId, Long courseId, String title, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unreviewed(String courseTitle, String title, int topics, int sections, boolean summarized) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestedMaterialView(Long materialId, String filename, Long courseId, String source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AmbiguityView(String mention, List<RequestedMaterialView> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExcludedTopicView(Long topicId, String title, String reason) {
    }
}
