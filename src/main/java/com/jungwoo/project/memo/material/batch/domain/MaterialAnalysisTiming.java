package com.jungwoo.project.memo.material.batch.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 지난 분석 한 건이 실제로 걸린 시간. material_analysis_timings.
 *
 * <p>예상 시간의 유일한 근거다. 원문·파일명·프롬프트는 남기지 않는다 — 추정에 필요한 것은
 * 분량(문자 수·청크 수)과 걸린 시간뿐이다.
 *
 * <p>큐에서 기다린 시간(queueWaitMs)을 모델 시간과 따로 적는 이유: 합쳐 두면 큐가 비어
 * 있을 때의 예상이 과대해진다. 화면도 "차례 대기"와 "분석 중"을 따로 말한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialAnalysisTiming {

    private Long timingId;
    private Long userId;
    private Long jobId;
    private String jobKind;
    private String extension;
    private Long sizeBytes;
    private Integer charCount;
    private Integer unitCount;
    private Integer chunkCount;
    private Long queueWaitMs;
    private Long extractMs;
    private Long modelMs;
    private Long persistMs;
    private Long totalMs;
    private String outcome;
    private LocalDateTime createdAt;
}
