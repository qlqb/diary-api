package com.jungwoo.project.memo.material.extract;

/**
 * 원문 추출의 상한. 넘긴 만큼은 {@link ExtractedDocument#warnings()}에 남긴다 — 일부만 읽고
 * 전부 읽은 것처럼 보이게 하지 않는다.
 *
 * <p>값의 근거: 단위 하나는 청크(기본 24,000자)에 두어 개가 들어갈 크기여야 하고, 문서 전체는
 * 자동 분석의 청크 상한(기본 12개)을 지나치게 넘기지 않아야 한다. 24,000 × 12 ≈ 288,000자가
 * 한 작업이 실제로 읽는 양이라, 전체 상한은 그보다 넉넉한 100만 자로 둔다.
 */
public final class ExtractionLimits {

    /** 단위 하나의 상한. 넘으면 같은 unit_no로 여러 단위가 된다(셀 번호·페이지 번호는 유지). */
    public static final int MAX_UNIT_CHARS = 12_000;
    /** 문단을 모아 만드는 블록 하나의 목표 크기(HWP·HWPX). */
    public static final int BLOCK_CHARS = 6_000;
    /** 한 문서에서 모으는 전체 글자 수 상한. */
    public static final int MAX_DOCUMENT_CHARS = 1_000_000;
    /** 한 문서의 단위 수 상한. */
    public static final int MAX_UNITS = 3_000;
    /** 노트북 셀 하나의 출력에서 가져오는 글자 수. */
    public static final int MAX_OUTPUT_CHARS = 2_000;
    /** 노트북 셀 하나에서 읽을 출력 개수. */
    public static final int MAX_OUTPUTS_PER_CELL = 5;
    /** HWPX 컨테이너(zip) 안에서 풀어 읽는 항목 수. */
    public static final int MAX_CONTAINER_ENTRIES = 512;
    /** HWPX 컨테이너 항목 하나의 해제 크기 상한. */
    public static final int MAX_CONTAINER_ENTRY_BYTES = 40 * 1024 * 1024;
    /** HWPX 컨테이너 전체의 해제 크기 상한. */
    public static final long MAX_CONTAINER_TOTAL_BYTES = 120L * 1024 * 1024;

    private ExtractionLimits() {
    }

    static String truncatedWarning(String what) {
        return what + "이(가) 상한을 넘어 뒷부분을 읽지 않았어요";
    }
}
