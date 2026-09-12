package com.jungwoo.project.memo.material.analysis;

/** 분석기가 던지는 실패. 종류가 재시도 정책을 정한다. */
public class AnalysisFailure extends RuntimeException {

    private final AnalysisFailureClassifier.Kind kind;

    public AnalysisFailure(AnalysisFailureClassifier.Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public AnalysisFailure(AnalysisFailureClassifier.Kind kind, String message) {
        this(kind, message, null);
    }

    public AnalysisFailureClassifier.Kind kind() {
        return kind;
    }
}
