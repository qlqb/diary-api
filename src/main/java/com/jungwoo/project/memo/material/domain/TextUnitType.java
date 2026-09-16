package com.jungwoo.project.memo.material.domain;

/** material_text_units.unit_type. 물리 단위의 종류 — 인쇄 쪽수가 아니다. */
public enum TextUnitType {
    PDF_PAGE,
    PPTX_SLIDE,
    /**
     * 페이지 구조를 알 수 없을 때 extracted_text를 문자 수 기준으로 나눈 블록. 원본 파일이 없는 옛 자료와,
     * 물리 페이지가 없는 형식(HWP는 쪽이 편집기 배치 결과라 파일에 없다, IPYNB, ZIP)이 여기에 해당한다.
     */
    TEXT_BLOCK;

    /** 화면과 프롬프트에서 쓰는 짧은 이름. */
    public String label() {
        return switch (this) {
            case PDF_PAGE -> "p.";
            case PPTX_SLIDE -> "슬라이드 ";
            case TEXT_BLOCK -> "구간 ";
        };
    }
}
