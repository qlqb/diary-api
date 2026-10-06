package com.jungwoo.project.memo.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 기록의 "어떻게 했나"를 고친다. 보내지 않은 값은 비운다(전체 교체) — 화면이 지금 보이는 값을 모두 보낸다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionRecordReflectionRequest {
    /** SOLO / GUIDED / null(남기지 않음). */
    private String supportLevel;
    private String stuckStep;
    private String blockerKind;
    private String note;
}
