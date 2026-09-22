package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 모델이 한 턴에서 내는 합의 변경 제안 하나. 서버가 검증해 {@link PlanBriefItem}에 반영한다 — 모델은 합의를 직접 쓰지 않는다.
 *
 * @param op              ADD / ACCEPT / REJECT / UPDATE / REMOVE
 * @param id              기존 항목 번호(ADD는 null)
 * @param kind            항목 종류(ADD)
 * @param text            한 문장(ADD/UPDATE)
 * @param speaker         USER / ASSISTANT(ADD). 모르면 서버가 ASSISTANT(후보)로 낮춘다
 * @param scope           THIS_DRAFT / PERIOD(ADD/UPDATE)
 * @param topicId         관련 학습 항목
 * @param courseId        관련 프로젝트
 * @param executionItemId 관련 실행 항목
 * @param periodStart     scope=PERIOD일 때 실제 시작 날짜(YYYY-MM-DD). "이번 주"는 발언 시점의 주(사용자 시간대)로 적는다
 * @param periodEnd       scope=PERIOD일 때 실제 종료 날짜(YYYY-MM-DD)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanBriefOp(
        String op,
        Integer id,
        String kind,
        String text,
        String speaker,
        String scope,
        Long topicId,
        Long courseId,
        Long executionItemId,
        String periodStart,
        String periodEnd,
        /** TIME_BUDGET만: 이번 계획에 쓸 수 있는 시간(분). */
        Integer minutes,
        /** TIME_BUDGET만: PLAN(계획 전체) / DAY(하루). */
        String per
) {
    public PlanBriefOp(String op, Integer id, String kind, String text, String speaker, String scope, Long topicId,
                       Long courseId, Long executionItemId, String periodStart, String periodEnd) {
        this(op, id, kind, text, speaker, scope, topicId, courseId, executionItemId, periodStart, periodEnd, null, null);
    }

    /** 날짜 없이 만드는 경로(기존 호출부·테스트). */
    public PlanBriefOp(String op, Integer id, String kind, String text, String speaker, String scope, Long topicId,
                       Long courseId, Long executionItemId) {
        this(op, id, kind, text, speaker, scope, topicId, courseId, executionItemId, null, null, null, null);
    }
}
