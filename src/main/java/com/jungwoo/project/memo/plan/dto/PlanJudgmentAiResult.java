package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 판단 호출에서 모델이 돌려주는 구조화 JSON.
 *
 * <p>enum이어야 할 값(취급, 근거 종류)을 <b>String으로 받는다.</b> Jackson이 모르는 값에서
 * 터지면 판단 전체를 잃는데, 우리가 원하는 것은 그 값 하나를 버리는 것이다. 문자열로 받아
 * {@link com.jungwoo.project.memo.plan.PlanJudgmentService}가 알아보는 것만 골라 쓴다.
 *
 * <p>goals가 리스트인 것도 같은 이유다. 모델에게 "기간이 둘이면 물어봐"라고 시키는 대신
 * "이 요청이 겨냥하는 목표 기간을 전부 적어라"라고 시키고, 그것이 둘 이상인지는 서버가
 * 센다 — 되묻기 판정을 모델 재량에 두지 않기 위해서다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanJudgmentAiResult(
        List<GoalPeriod> goals,
        String strategySummary,
        List<AiCourse> courses,
        List<AiTopic> topics,
        List<String> planningRules,
        List<Long> referencedContextIds
) {

    /**
     * 이 요청이 겨냥하는 목표 기간 하나.
     *
     * @param until 그 목표의 끝을 사람 말로 적은 것("다음 주 화요일 수업", "중간고사"). 서버는
     *              이 값을 날짜로 해석하지 않고 되묻기 문구에만 쓴다 — 날짜는 요청이 이미 갖고 있다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoalPeriod(String goal, String until) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiCourse(Long courseId, Integer rank, String focus, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiTopic(
            Long topicId,
            /** FULL | SKIM | SKIP. 모르는 값이면 그 항목의 판단을 버린다(= 근거 없음 = FULL). */
            String treatment,
            Integer rank,
            String reason,
            List<AiEvidence> evidence
    ) {
    }

    /**
     * 취급을 그렇게 정한 근거 하나.
     *
     * @param type      PROGRESS | NEXT_CLASS | CONTEXT | USER_MARK | DEADLINE
     * @param refId     그 근거가 가리키는 행. CONTEXT면 context_id, USER_MARK/PROGRESS면 topic_id
     * @param scopeMatch CONTEXT일 때만. DIRECT면 이 맥락이 이 학습 항목의 범위를 직접 덮는다는
     *                   주장이고, BROAD면 넓거나 간접적이라는 뜻이다. 서버는 이 주장을 그대로
     *                   믿되 <b>그 맥락 행이 실제로 있고 아직 유효한지</b>는 직접 대조한다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiEvidence(String type, String value, Long refId, String scopeMatch) {
    }
}
