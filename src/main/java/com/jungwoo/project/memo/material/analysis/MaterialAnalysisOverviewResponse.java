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

    /**
     * 오늘의 분석 한도. reached면 대기 중인 작업은 실패나 영구 정지가 아니라 resumesAt부터 이어서 처리된다.
     * 화면은 이 값을 보고 "한도 때문에 대기 중"과 그 시각을 말한다.
     */
    private Limit limit;

    @Getter
    @Builder
    public static class Limit {
        private int contentUsed;
        private int contentLimit;
        private int linkUsed;
        private int linkLimit;
        private boolean reached;
        private java.time.LocalDateTime resumesAt;
    }
}
