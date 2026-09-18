package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 계획 초안 생성 호출(최종 계획 호출)에서 모델이 돌려주는 구조화 JSON.
 *
 * <p>2026-09-17부터 최종 호출은 <b>전략과 실행 항목을 함께</b> 낸다. 전략은 "이번 기간을 왜 이렇게 운영하는가"
 * (목표·도달점·유지한 결정·우선순위·줄인 범위·가정·질문·읽지 못한 것·이전과 달라진 점)이고, 항목은 그 전략을 실행하는
 * 조각이다. 둘을 따로 부르지 않는다 — 본문을 읽은 같은 판단이 둘 다 내야 서로 어긋나지 않는다.
 *
 * <p>모델 출력을 그대로 신뢰하지 않는다. 인용 번호·courseId·마감 참조·기존 항목 참조는 서버가 회차 안의 값과 대조한다.
 * targetMinutes/targetMinutesReason은 예전 스키마의 잔재라 읽어도 쓰지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDraftAiResult(
        String title,
        String goalSummary,
        Integer targetMinutes,
        String targetMinutesReason,
        StrategyOut strategy,
        List<PlanDraftAiItem> items,
        /** 이 기간에 이미 있는 계획 항목([이 기간에 이미 있는 일정])을 어떻게 할지. 없으면 전부 유지. */
        List<ExistingItemOut> existingItems,
        /** 최종 판단에 핵심 근거가 더 필요할 때의 추가 읽기 요청. 서버가 상한 안에서 한 번만 들어준다. */
        MoreEvidenceOut moreEvidence
) {

    /**
     * 계획 전체의 판단.
     *
     * @param goal           사용자가 원하는 목표
     * @param reach          이번 기간의 현실적인 도달점
     * @param summary        이번 기간 운영 한두 문장
     * @param keptDecisions  유지한 사용자 결정·제약(합의·지시에서 온 것)
     * @param courses        과목 간 우선순위와 이유
     * @param deferred       중요한 후보 중 이번에 미루거나 줄인 범위와 이유(refIds는 그 학습 항목·구간의 인용 번호)
     * @param assumptions    확인되지 않은 가정
     * @param questions      원인·조건에 따라 계획이 달라지는 질문(최대 1개)
     * @param unread         읽지 못한 중요한 자료·범위
     * @param changes        이전 초안/합의에서 달라진 점과 근거
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrategyOut(
            String goal,
            String reach,
            String summary,
            List<String> keptDecisions,
            List<CourseOut> courses,
            List<DeferredOut> deferred,
            List<String> assumptions,
            List<String> questions,
            List<String> unread,
            List<ChangeOut> changes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourseOut(Long courseId, Integer rank, String focus, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeferredOut(String title, String reason, List<String> refIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeOut(String what, String why) {
    }

    /**
     * 초안 항목 하나. scheduledDate는 모델이 "이건 그날 해야 한다"고 판단한 경우에만 값이
     * 있고, 없으면 서버가 UNSCHEDULED + 계획 기간으로 만든다.
     *
     * @param doneCriteria     확인 가능한 완료 기준(없으면 description의 "완료:" 뒤를 서버가 나눈다)
     * @param actionType       READ / PRACTICE / RECALL / LAB
     * @param deadlineRefId    마감 근거 줄의 인용 번호([다음 수업]·[판단에 필요한 사실]의 과제). 서버가 그 사실의 시각을 붙인다
     * @param targetCompleteAt AI가 제안하는 완료 목표 시각("YYYY-MM-DDTHH:mm"). 사용자가 고칠 수 있는 제안이다
     * @param reflects         이전 실행 결과에서 무엇을 반영했는지(없으면 null)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanDraftAiItem(
            String title,
            String description,
            String doneCriteria,
            String actionType,
            Integer expectedMinutes,
            String priority,
            Long courseId,
            String scheduledDate,
            String reason,

            /**
             * 이 항목의 근거가 된 입력 줄의 인용 번호. 프롬프트 각 줄 끝 대괄호 값이다.
             *
             * <p>서버는 이번 생성 회차에 실제로 제공한 값만 인정하고 나머지는 버린다. 모델이
             * 파일명·쪽수 같은 문자열을 자유롭게 쓰는 것보다 회차 안의 번호를 인용하게 하는
             * 편이, 대조할 것이 서버가 이미 아는 집합 하나로 줄어든다.
             *
             * <p>이 필드가 없던 시절의 응답도 그대로 읽힌다 — null이면 근거 없는 항목이다.
             */
            List<String> refIds,
            String deadlineRefId,
            String targetCompleteAt,
            String reflects
    ) {
        /** 예전 스키마(행동·완료 기준·마감 참조 없음)로 만드는 생성자. 테스트 픽스처가 쓴다. */
        public PlanDraftAiItem(String title, String description, Integer expectedMinutes, String priority,
                               Long courseId, String scheduledDate, String reason, List<String> refIds) {
            this(title, description, null, null, expectedMinutes, priority, courseId, scheduledDate, reason, refIds,
                    null, null, null);
        }
    }

    /**
     * 기존 계획 항목에 대한 결정.
     *
     * @param refId          [이 기간에 이미 있는 일정] 줄의 인용 번호
     * @param action         KEEP / REDUCE / MOVE / DROP
     * @param expectedMinutes REDUCE일 때 줄인 분량
     * @param toDate         MOVE일 때 옮길 날짜("YYYY-MM-DD", 계획 기간 안)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExistingItemOut(String refId, String action, Integer expectedMinutes, String toDate, String reason) {
    }

    /**
     * @param sectionIds       [더 읽을 수 있는 구간]의 핸들(m7 등)
     * @param adjacentOfRefIds 이미 읽은 구간의 인용 번호 — 그 앞뒤 구간을 더 읽고 싶을 때
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoreEvidenceOut(List<String> sectionIds, List<String> adjacentOfRefIds, String reason) {
        public boolean isEmpty() {
            return (sectionIds == null || sectionIds.isEmpty()) && (adjacentOfRefIds == null || adjacentOfRefIds.isEmpty());
        }
    }

    /** 예전 5필드 생성자(테스트 픽스처 호환). */
    public PlanDraftAiResult(String title, String goalSummary, Integer targetMinutes, String targetMinutesReason,
                             List<PlanDraftAiItem> items) {
        this(title, goalSummary, targetMinutes, targetMinutesReason, null, items, null, null);
    }
}
