package com.jungwoo.project.memo.planning.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/** "이번 주에 공부하게 계획해줘" 같은 자연어 지시(선택). 없으면 기본 7일 범위로 배치한다. */
@Getter
@NoArgsConstructor
public class PlanningDraftRequest {

    private String instruction;
}
