package com.jungwoo.project.memo.orchestration;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RecommendAndPlanRequest {
    /** 예: "이번 주에 공부하게 계획해줘". 없으면 기본 7일 범위로 배치한다. */
    private String planningInstruction;
}
