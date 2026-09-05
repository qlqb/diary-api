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
 * <p>이 JSON은 스키마 없는 컬럼에 저장되고 몇 달 뒤에도 읽혀야 하므로 모르는 필드는
 * 무시하고 읽는다(PlanSnapshotItem과 같은 이유).
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
        List<String> planningRules
) {

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

    /** 학습 항목 하나의 취급과 그 근거. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicTreatment(
            Long topicId,
            Treatment treatment,
            int rank,
            String reason,
            List<Evidence> evidence
    ) {
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
}
