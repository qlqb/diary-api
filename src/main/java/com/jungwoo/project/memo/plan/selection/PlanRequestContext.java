package com.jungwoo.project.memo.plan.selection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jungwoo.project.memo.plan.domain.FamiliarityAnswer;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;

import java.time.LocalDate;
import java.util.List;

/**
 * 초안을 만든 요청 그대로. ai_proposals.plan_request_json에 남아 <b>같은 조건으로 다시 만들기</b>(이번만 빼기·되돌리기·
 * 이미 알아요 뒤 재생성)에 쓰인다. 계획 화면과 상담이 같은 모양이다 — 상담 초안을 다시 만들 때 화면의 기본 날짜와 빈
 * 지시로 요청을 새로 조립하지 않기 위해서다.
 *
 * @param source              PLAN_SCREEN / CONVERSATION
 * @param intensity           서버가 승계까지 끝낸 강도(다시 만들 때 직전 계획이 바뀌어도 같은 강도로)
 * @param instruction         서버가 모델에 넘긴 지시 그대로(상담이면 대화 요약)
 * @param requestedMaterialIds 화면·되묻기로 지정한 자료. 지시 문장에서 찾은 자료는 넣지 않는다 — 지시가 그대로 남으므로
 *                            다시 만들 때 같은 규칙으로 다시 찾는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanRequestContext(
        int version,
        String source,
        LocalDate startDate,
        LocalDate endDate,
        PlanIntensity intensity,
        String title,
        String instruction,
        List<Long> courseIds,
        List<Long> excludeTopicIds,
        List<Long> requestedMaterialIds,
        List<Long> requestedSectionIds,
        FamiliarityAnswer familiarityAnswer,
        List<Long> familiarityTopicIds,
        Long conversationId
) {
    public static final int VERSION = 1;
}
