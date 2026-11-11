package com.jungwoo.project.memo.material.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자가 분석 결과를 검토하며 수정한 내용. payload 구조는 MaterialAnalysisPayload와 동일해야 한다. */
@Getter
@NoArgsConstructor
public class MaterialAnalysisEditRequest {

    @NotNull
    private MaterialAnalysisPayload payload;
}
