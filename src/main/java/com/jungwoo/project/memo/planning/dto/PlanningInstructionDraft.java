package com.jungwoo.project.memo.planning.dto;

/** 사용자의 자연어 계획 지시를 해석한 결과. AI = 의도 해석만, 실제 시간 계산은 Solver가 한다. */
public record PlanningInstructionDraft(Integer horizonDays, Integer expectedMinutesOverride, String reason) {
}
