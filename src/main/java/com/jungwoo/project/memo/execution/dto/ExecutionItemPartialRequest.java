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
}
