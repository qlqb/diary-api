package com.jungwoo.project.memo.material.batch.domain;

/**
 * material_analysis_batch_items.upload_state — 이 자리가 자료가 되기까지.
 *
 * <p>분석이 어디까지 갔는지는 여기 없다. 그건 material_analysis_jobs가 안다. 여기는
 * "파일이 자료 행이 됐는가"까지다.
 *
 * <p>NO_TEXT는 실패가 아니다 — 파일은 저장됐고 자료 행도 있다. 다만 본문을 읽지 못해
 * 분석 대상이 아니다. UPLOAD_FAILED와 구분해야 사용자가 같은 파일을 반복해 올리지 않는다.
 */
public enum BatchItemUploadState {
    STAGED,
    UPLOADING,
    UPLOADED,
    UPLOAD_FAILED,
    UNSUPPORTED,
    NO_TEXT,
    ABANDONED;

    /** 분석 진행률의 분모에 드는가. 올라가지 못했거나 읽을 본문이 없는 자리는 빠진다. */
    public boolean countsTowardAnalysis() {
        return this == UPLOADED;
    }

    public boolean isSettled() {
        return this != STAGED && this != UPLOADING;
    }
}
