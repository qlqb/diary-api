package com.jungwoo.project.memo.plan.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이 계획 버전을 만든 판단. plan_versions.strategy_json에 그대로 저장된다.
 *
 * <p>"무엇을 할 것인가"(items_snapshot) 옆에 "왜 그렇게 하기로 했는가"를 둔다. 이 값이
 * 없으면 다음 계획을 만들 때 지난번에 왜 그 과목을 먼저 뒀는지, 왜 어떤 내용을 건너뛰었는지
 * 아무도 모른다 — 사용자도, 재계획도.
 *
 * <p>★ 판단은 <b>학습 항목(topic) 단위까지만</b> 낸다. 어느 자료의 몇 쪽을 볼지는 조각
 * 생성의 몫이다. 이 경계를 흐리면 판단이 실행 세부에 묶여 재사용할 수 없게 된다 — 자료가
 * 바뀌어도 "다음 수업 전에 재귀를 먼저 본다"는 판단은 그대로여야 한다.
 *
 * <p>(2026-09-17) 기본 AI 경로의 최종 계획 호출도 이 판단을 낸다. 그때 추가된 필드(reach·keptDecisions·deferred·
 * assumptions·openQuestions·unreadNotes·changes·existingDecisions)는 판단 경로(V1)의 전략에서는 비어 있다.
 * 이 JSON은 스키마 없는 컬럼에 저장되고 몇 달 뒤에도 읽혀야 하므로 모르는 필드는 무시하고 읽는다
 * (PlanSnapshotItem과 같은 이유). 예전 판에 없던 필드는 null로 읽힌다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStrategy(

        /** 이 기간에 현실적으로 무엇을 이룰 것인가. "밀린 것 전부"가 자동 목표가 되지 않는다. */
        String goal,

        /** 그 목표를 위해 이번 기간을 어떻게 운영하는가. 한두 문장. */
        String strategySummary,

        StrategySource strategySource,

        /** REUSED일 때 이 판단을 물려준 plan_version_id. NEW면 null. */
        Long reusedFromVersionId,

        /**
         * 근거로 삼은 user_contexts의 id.
         *
         * ★ 내용을 복사하지 않고 id만 남긴다. 맥락은 나중에 바뀌거나 STALE이 되는데, 여기
         * 문장을 복사해 두면 "그때 그렇게 알고 있었다"와 "지금도 그렇다"가 구분되지 않는다.
         */
        List<Long> referencedContextIds,

        List<CourseStrategy> courses,

        List<TopicTreatment> topics,

        /**
         * 이 기간 내내 지켜야 할 규칙. "다음 수업 전 선수내용 완료", "알바 종료 후 무거운
         * 학습 없음"처럼 조각 하나가 아니라 계획 전체에 걸리는 조건이다.
         */
        List<String> planningRules,

        /** 이번 기간의 현실적인 도달점(목표와 다를 수 있다). */
        String reach,

        /** 유지한 사용자 결정·제약. 상담 합의·지시에서 온 문장이다. */
        List<String> keptDecisions,

        /** 중요한 후보 중 이번에 미루거나 줄인 범위와 이유. topicId는 학습 항목을 가리킬 때만 있다. */
        List<Deferred> deferred,

        /** 확인되지 않은 가정. 확정 사실로 승격하지 않는다. */
        List<String> assumptions,

        /** 원인·조건에 따라 계획이 달라지는 질문. 답 없이도 초안은 만들어졌다. */
        List<String> openQuestions,

        /** 읽지 못한 중요한 자료·범위(모델이 말한 것 + 서버가 상한 때문에 싣지 못한 것). */
        List<String> unreadNotes,

        /** 이전 초안·합의에서 달라진 점과 그 근거. */
        List<Change> changes,

        /** 이 기간에 이미 있던 계획 항목을 어떻게 하기로 했는가(유지·줄임·이동·제외). */
        List<ExistingDecision> existingDecisions
) {

    /** 새 필드 없이 만드는 경로(판단 경로·예전 테스트). */
    public PlanStrategy(String goal, String strategySummary, StrategySource strategySource, Long reusedFromVersionId,
                        List<Long> referencedContextIds, List<CourseStrategy> courses, List<TopicTreatment> topics,
                        List<String> planningRules) {
        this(goal, strategySummary, strategySource, reusedFromVersionId, referencedContextIds, courses, topics,
                planningRules, null, null, null, null, null, null, null, null);
    }

    /** 과목 하나를 이번 기간에 어떻게 볼 것인가. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourseStrategy(
            Long courseId,
            /** 1이 가장 먼저. 정렬 힌트일 뿐이라 같은 값이 겹쳐도 된다. */
            int rank,
            String focus,
            String reason
    ) {
    }

    /**
     * 학습 항목 하나의 취급과 그 근거.
     *
     * @param topicTitle 그때 그 항목의 이름. 표시용 복사본이다 — 항목 이름이 나중에 바뀌어도
     *                   이 판단은 그때 이름을 유지한다(PlanSnapshotItem.courseTitle과 같은 이유).
     *                   화면이 제외 사유를 보여줄 때 이 값을 쓴다
     * @param adjustedBy 모델이 낸 취급을 서버가 되돌렸으면 SERVER. 화면이 "당신이 표시해서
     *                   이렇게 됐다"와 "모델이 그렇게 봤다"를 구분해 말하는 데 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicTreatment(
            Long topicId,
            Treatment treatment,
            int rank,
            String reason,
            List<Evidence> evidence,
            String topicTitle,
            AdjustedBy adjustedBy
    ) {

        /** 이름·조정 주체 없이 만드는 경로. 이 필드가 생기기 전 코드와 테스트가 쓴다. */
        public TopicTreatment(Long topicId, Treatment treatment, int rank, String reason,
                              List<Evidence> evidence) {
            this(topicId, treatment, rank, reason, evidence, null, null);
        }

        public TopicTreatment withTreatment(Treatment newTreatment, String newReason,
                                            List<Evidence> newEvidence, AdjustedBy by) {
            return new TopicTreatment(topicId, newTreatment, rank, newReason, newEvidence, topicTitle, by);
        }
    }

    /**
     * 취급을 그렇게 정한 근거 하나.
     *
     * @param value 사람이 읽을 수 있는 근거 문장("9/9 10:00 웹서버프로그래밍 수업")
     * @param refId 그 근거가 가리키는 행의 id. CONTEXT면 context_id, NEXT_CLASS면 routine_id.
     *              가리킬 행이 없으면 null이다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(
            EvidenceType type,
            String value,
            Long refId
    ) {
    }

    /** 이번에 미루거나 줄인 범위. "이미 안다"(user_mark)와 다른 이유다 — 시간이 적어 이번엔 하지 않는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Deferred(String title, String reason, Long topicId, Long sectionId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(String what, String why) {
    }

    /**
     * 기존 계획 항목에 대한 결정.
     *
     * @param action KEEP / REDUCE / MOVE / DROP. KEEP 외는 제안의 조정 항목으로도 들어간다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExistingDecision(Long executionItemId, String title, String action, String reason,
                                   Integer expectedMinutes, java.time.LocalDate toDate) {
    }
}
