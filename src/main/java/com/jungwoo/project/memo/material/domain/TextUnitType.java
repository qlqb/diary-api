package com.jungwoo.project.memo.material.domain;

/** material_text_units.unit_type. 파일에서 확인한 단위의 종류 — 인쇄 쪽수가 아니다. */
public enum TextUnitType {
    PDF_PAGE,
    PPTX_SLIDE,
    /** Jupyter 노트북의 셀. 번호는 원본 순서의 1-based 셀 번호이고 execution_count가 아니다. */
    NOTEBOOK_CELL,
    /**
     * 페이지 구조가 없을 때 문단·문자 수 기준으로 나눈 블록. 원본 파일이 없는 옛 자료와, 파일 안에
     * 물리 페이지가 없는 한글 문서(HWP·HWPX — 쪽은 편집기가 배치로 계산한 결과다)가 여기에 해당한다.
     */
    TEXT_BLOCK;

    /** 화면과 프롬프트에서 쓰는 짧은 이름. */
    public String label() {
        return switch (this) {
            case PDF_PAGE -> "p.";
            case PPTX_SLIDE -> "슬라이드 ";
            case NOTEBOOK_CELL -> "셀 ";
            case TEXT_BLOCK -> "구간 ";
        };
    }
}
