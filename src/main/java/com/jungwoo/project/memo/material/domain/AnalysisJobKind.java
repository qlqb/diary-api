package com.jungwoo.project.memo.material.domain;

/** material_analysis_jobs.job_kind. */
public enum AnalysisJobKind {
    /** 프로젝트와 무관한 원문 분석 — 구간·문항·날짜 단서. 자료당 파일 해시마다 한 번. */
    CONTENT,
    /** (자료 × 프로젝트) 맥락의 토픽 연결·구조 변경안. CONTENT가 끝난 뒤 링크마다 한 번. */
    LINK
}
