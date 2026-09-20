package com.jungwoo.project.memo.execution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "일부 수행" 기록 요청.
 *
 * 서버가 실제로 한 만큼을 PARTIAL 결과로 남기고, 남은 분량은 별도 조각으로 분리한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemPartialRequest {

    @NotNull
    private Long version;

    /** 1~99. 생략하면 50으로 본다. */
    private Integer completionPercent;

    private Integer actualMinutes;

    private String note;
    /**
     * 다 하지 못했거나 어려웠던 이유(선택): TIME(시간이 없었다) / CONCEPT(개념에서 막혔다) / ENERGY(컨디션) / OTHER.
     * 묻지 않았거나 답하지 않았으면 null이다 — "시간이 없었다"와 "개념에서 막혔다"는 다음 계획을 다르게 바꾼다.
     */
    private String blockerKind;
}
