package com.jungwoo.project.memo.material.zip.domain;

/** 압축 안 파일 하나의 가져오기 상태. */
public enum ZipEntryStatus {
    /** 목록에는 있고 아직 고르지 않았다. */
    PENDING,
    /** 사용자가 골라 확정했다. 작업자가 집어 가기를 기다린다. */
    QUEUED,
    /** 작업자가 선점해 자료로 만드는 중. */
    IMPORTING,
    /** 자료가 됐다. material_id가 채워져 있다. */
    DONE,
    /** 자료로 만들지 못했다. 이 항목만 다시 시도할 수 있다. */
    FAILED,
    /** 이번 지원 대상이 아니거나(확장자·디렉터리·메타데이터) 경로가 모호해 고를 수 없다. */
    UNSUPPORTED
}
