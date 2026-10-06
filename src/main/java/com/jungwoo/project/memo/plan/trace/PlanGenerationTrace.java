package com.jungwoo.project.memo.plan.trace;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** plan_generation_traces 한 행 — 생성 회차의 모델 호출 하나에 실제로 보낸 입력. */
@Getter
@Setter
@NoArgsConstructor
public class PlanGenerationTrace {
    private Long traceId;
    private Long userId;
    private String generationId;
    private String callKind;
    private int callOrder;
    private String apiCommit;
    private String modelName;
    private Integer estimatedTokens;
    private String systemPrompt;
    private String userPrompt;
    private String promptSha256;
    private LocalDateTime textPurgedAt;
    private String shownJson;
    private String materialIds;
    private LocalDateTime createdAt;
}
