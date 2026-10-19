package com.jungwoo.project.memo.material.batch.domain;

/**
 * material_analysis_batches.status.
 *
 * <p>FINISHED는 "처리가 끝났다"이지 "전부 성공했다"가 아니다 — 실패·제외가 섞여 있어도
 * 더 돌 것이 없으면 FINISHED다. 화면은 성공/실패 수를 따로 말한다.
 */
public enum BatchStatus {
    /** 파일을 고르기만 했다. 아직 아무것도 올리지 않았다. */
    STAGED,
    UPLOADING,
    ANALYZING,
    FINISHED,
    /** 아무것도 올리지 않은 채 사용자가 떠났다. 조회 대상이 아니다. */
    ABANDONED;

    public boolean isOpen() {
        return this == STAGED || this == UPLOADING || this == ANALYZING;
    }
}
