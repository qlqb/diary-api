package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 계획 초안 생성 호출에서 모델이 돌려주는 구조화 JSON.
 *
 * targetMinutes는 프리셋 기준선이 아니라 모델이 사용자 상황(instruction, 고정 일정, 직전
 * 회고)을 보고 조정한 최종값이다. 조정하지 않았으면 기준선과 같은 값이 온다.
 * targetMinutesReason은 조정했을 때만 값이 있고, 초안 응답으로 전달만 하고 영속하지 않는다 —
 * 확정 이후에는 스냅샷의 항목 합이 사실을 말한다.
 *
 * 모델이 이 필드들을 빼먹거나 이상한 값을 주면 서버가 기준선으로 되돌린다. 모델 출력을
 * 그대로 신뢰하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDraftAiResult(
        String title,
        String goalSummary,
        Integer targetMinutes,
        String targetMinutesReason,
        List<PlanDraftAiItem> items
) {

    /**
     * 초안 항목 하나. scheduledDate는 모델이 "이건 그날 해야 한다"고 판단한 경우에만 값이
     * 있고, 없으면 서버가 UNSCHEDULED + 계획 기간으로 만든다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanDraftAiItem(
            String title,
            String description,
            Integer expectedMinutes,
            String priority,
            Long courseId,
            String scheduledDate,
            String reason
    ) {
    }
}
