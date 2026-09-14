package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 조각 생성 호출에서 모델이 돌려주는 구조화 JSON.
 *
 * <p>모델이 정하는 것은 <b>무엇을 하고 무엇을 하면 끝인가</b>뿐이다. 어느 자료의 어디인지는
 * 학습 항목이 이미 알고 있으므로 서버가 붙이고, 마감도 서버가 아는 값(다음 수업)에서 온다.
 * 모델에게 고르게 할수록 대조할 것이 늘어나는데, 여기서 대조할 수 있는 것은 이미 서버가
 * 알고 있는 것뿐이다 — 그러면 애초에 서버가 채우는 편이 짧다.
 *
 * <p>enum이어야 할 값(행동 종류)을 String으로 받는 이유는 판단 호출과 같다. 모르는 값에서
 * 파싱이 통째로 실패하는 것보다 그 조각 하나를 다루는 편이 낫다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanItemAiResult(String title, List<AiItem> items) {

    /**
     * @param topicId       [학습 항목]에 있는 값이어야 한다. 없는 값이면 그 조각을 버린다
     * @param actionType    READ | PRACTICE | RECALL | LAB
     * @param doneCriteria  무엇을 하면 끝인가. "공부하기" 같은 문장은 완료 기준이 아니다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiItem(
            Long topicId,
            String title,
            String description,
            String doneCriteria,
            String actionType,
            Integer expectedMinutes,
            String priority,
            String reason
    ) {
    }
}
