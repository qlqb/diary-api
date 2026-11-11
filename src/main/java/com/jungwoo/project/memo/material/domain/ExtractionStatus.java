package com.jungwoo.project.memo.material.domain;

public enum ExtractionStatus {
    PENDING,
    SUCCESS,
    /** 텍스트 레이어가 없는 스캔 이미지 PDF 등. OCR은 이번 범위에 없다 — 실패를 명확히 보여준다. */
    FAILED_NO_TEXT,
    FAILED
}
