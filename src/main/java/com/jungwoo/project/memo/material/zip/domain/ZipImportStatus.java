package com.jungwoo.project.memo.material.zip.domain;

/**
 * 압축 파일 가져오기 한 건의 상태. AI 분석 상태와는 다른 축이다 — 여기서 COMPLETED는 "자료가
 * 만들어졌다"이지 "분석이 끝났다"가 아니다.
 */
public enum ZipImportStatus {
    /** 목록을 준비하는 중(업로드 직후). */
    PREPARING,
    /** 목록이 준비돼 사용자의 선택을 기다린다. 아직 자료가 아니므로 분석도 시작하지 않는다. */
    READY,
    /** 선택을 확정해 자료를 만드는 중. */
    IMPORTING,
    /** 선택한 항목이 모두 자료가 됐다. */
    COMPLETED,
    /** 일부만 자료가 됐다. 실패한 항목만 다시 시도할 수 있다. */
    PARTIAL,
    /** 압축 자체를 열지 못했거나 선택한 항목이 전부 실패했다. */
    FAILED,
    /** 사용자가 취소했다. 만들어진 자료는 그대로 남는다. */
    CANCELLED,
    /** 보관 기한이 지나 원본 압축 파일을 지웠다. 이미 만든 자료는 그대로 남는다. */
    EXPIRED;

    public boolean terminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
