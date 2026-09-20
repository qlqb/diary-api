package com.jungwoo.project.memo.ai.consult;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 상담 모델이 구조화 출력의 {@code consult}에 내는 것(검증 전). 서버는 이것을 그대로 믿지 않는다 —
 * {@link ConsultTurnService}가 인용·소유권·철회 여부를 확인하고 화면용 {@link ConsultView}로 바꾼다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsultOut(QuestionOut question, DirectionOut direction, List<MemoryOut> memory, ActivityOut activity) {

    /**
     * @param text        이번 턴의 질문 한 가지(reply의 질문과 같은 내용)
     * @param why         이 답이 계획의 무엇을 바꾸는지 — 필요할 때만, 짧게
     * @param topic       SUPPORT_LEVEL / BLOCKER / TIME / SCOPE / DEPTH / SUBMISSION / OTHER
     * @param choices     빠른 답 제안. 모든 경우를 열거한 것이 아니다 — 사용자는 언제나 자유롭게 답할 수 있다
     * @param multiSelect 여러 개를 함께 고를 수 있는 질문인가
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestionOut(String text, String why, String topic, List<String> choices, Boolean multiSelect) {
    }

    /**
     * 이번 답변으로 계획 방향이 어떻게 바뀌었는가. 아직 확인하지 않은 파일·문제 조건은 적지 않는다.
     *
     * @param affectsDraft 이미 만든 초안의 분량·대상·마감·활동 방식에 영향을 주는가
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectionOut(String before, String after, String reason, Boolean affectsDraft) {
    }

    /**
     * 기억할 사용자 상황 한 줄.
     *
     * @param evidenceType STATED(사용자가 직접 말함) / SELF_REPORT(자기평가) / INFERRED(네 추정)
     * @param quote        사용자가 이번 메시지에서 실제로 쓴 말의 일부(STATED·SELF_REPORT의 근거)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryOut(String text, String evidenceType, Long courseId, String scopeStart, String scopeEnd,
                            String quote) {
    }

    /** 선택 활동(예: 주제 목록을 보고 알아/애매해/처음 봐로 답하기). 필수가 아니다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityOut(String kind, Long courseId, String title, List<ActivityItemOut> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityItemOut(String label, Long topicId, Long sectionId) {
    }
}
