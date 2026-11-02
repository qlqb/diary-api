package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * 문단을 모아 "구간 N" 블록 단위로 만든다(HWP·HWPX).
 *
 * <p>한글 문서에는 파일 안에 물리 페이지가 없다 — 쪽은 편집기가 글꼴·용지로 계산한 결과다.
 * 그래서 문단 번호를 페이지처럼 부르지 않고, 문단 경계에서만 끊은 분량 블록을 쓴다. 위치는
 * "구간 3"이고 그것이 사실이다.
 *
 * <p>표는 한 덩어리로 다룬다 — 블록 경계가 표 중간을 가르면 제출일과 조건이 다른 블록으로
 * 흩어져 "9/25"가 어느 행의 값인지 알 수 없게 된다.
 */
final class TextBlockCollector {

    private final ExtractedDocument.Builder builder;
    private final List<String> pending = new ArrayList<>();
    private int pendingChars;
    private int blockNo;
    private boolean truncated;

    TextBlockCollector(ExtractedDocument.Builder builder) {
        this.builder = builder;
    }

    /** 문단 하나. 표는 {@link #addAtomic(String)}로 넣는다. */
    void addParagraph(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        add(text.stripTrailing(), false);
    }

    /** 쪼개지 않고 한 블록 안에 두려는 덩어리(표). 혼자서 상한을 넘으면 그때만 나눈다. */
    void addAtomic(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        add(text.stripTrailing(), true);
    }

    private void add(String text, boolean atomic) {
        if (truncated) {
            return;
        }
        if (pendingChars + text.length() > ExtractionLimits.BLOCK_CHARS && pendingChars > 0) {
            flush();
        }
        if (atomic && text.length() > ExtractionLimits.MAX_UNIT_CHARS) {
            for (int pos = 0; pos < text.length(); pos += ExtractionLimits.MAX_UNIT_CHARS) {
                pending.add(text.substring(pos, Math.min(text.length(), pos + ExtractionLimits.MAX_UNIT_CHARS)));
                pendingChars = ExtractionLimits.BLOCK_CHARS;
                flush();
            }
            return;
        }
        pending.add(text);
        pendingChars += text.length() + 1;
        if (pendingChars >= ExtractionLimits.BLOCK_CHARS) {
            flush();
        }
    }

    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        if (builder.textLength() >= ExtractionLimits.MAX_DOCUMENT_CHARS || blockNo >= ExtractionLimits.MAX_UNITS) {
            if (!truncated) {
                builder.warn(ExtractionLimits.truncatedWarning("문서 분량"));
                truncated = true;
            }
            pending.clear();
            pendingChars = 0;
            return;
        }
        blockNo++;
        String text = String.join("\n", pending);
        builder.unit(TextUnitType.TEXT_BLOCK, blockNo, "--- 구간 " + blockNo + " ---", text);
        pending.clear();
        pendingChars = 0;
    }

    boolean isTruncated() {
        return truncated;
    }
}
