package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;

/**
 * 분석기의 정상 종료 결과. 실패는 {@link AnalysisFailure}로 던진다.
 *
 * @param leaseLost 임대를 잃어 아무것도 저장하지 않고 물러났다. 종료 기록도 하지 않는다.
 */
public record AnalysisOutcome(AnalysisJobStatus status, String errorCode, String message, Long resultRefId,
                              boolean leaseLost) {

    public static AnalysisOutcome done(String message, Long resultRefId) {
        return new AnalysisOutcome(AnalysisJobStatus.DONE, null, message, resultRefId, false);
    }

    public static AnalysisOutcome partial(String code, String message) {
        return new AnalysisOutcome(AnalysisJobStatus.PARTIAL, code, message, null, false);
    }

    public static AnalysisOutcome cancelled(String message) {
        return new AnalysisOutcome(AnalysisJobStatus.CANCELLED, "INVALID_TARGET", message, null, false);
    }

    public static AnalysisOutcome failed(String code, String message) {
        return new AnalysisOutcome(AnalysisJobStatus.FAILED, code, message, null, false);
    }

    public static AnalysisOutcome lost() {
        return new AnalysisOutcome(null, null, null, null, true);
    }
}
