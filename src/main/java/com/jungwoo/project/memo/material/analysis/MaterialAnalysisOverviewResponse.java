package com.jungwoo.project.memo.material.analysis;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 자료함 상단의 상태 요약 한 줄. "분석 중 2 · 대기 5 · 완료 16" + 일시중지/재개.
 * serviceAvailable=false면 모델이 설정되지 않아 처리가 멈춰 있다는 뜻이다(등록은 유지된다).
 */
@Getter
@Builder
public class MaterialAnalysisOverviewResponse {
    private boolean paused;
    private boolean serviceAvailable;
    private int queued;
    private int running;
    private int partial;
    private int done;
    private int failed;
    private int unavailable;
    private List<MaterialAnalysisStatusResponse> materials;
}
