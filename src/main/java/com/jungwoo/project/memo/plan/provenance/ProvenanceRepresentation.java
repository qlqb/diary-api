package com.jungwoo.project.memo.plan.provenance;

/**
 * 원본을 모델에 어떤 모양으로 줬는가. 스냅샷을 읽는 쪽이 "이게 원본 전체인가"를 오해하지
 * 않게 하는 값이다.
 */
public enum ProvenanceRepresentation {

    /** 행에서 필요한 필드만 골라 줬다. 원본 전체가 아니다. */
    SELECTED_FIELDS,

    /** 여러 행을 서버가 한 줄로 접어서 줬다(예: 하루 요약). */
    SUMMARY_LINE,

    /** 원문의 일부를 그대로 발췌해 줬다. */
    EXCERPT
}
