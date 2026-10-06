package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * 파일 한 번 읽기의 결과. 전체 텍스트와 단위 텍스트를 같은 파싱에서 함께 만든다.
 *
 * <p>둘을 따로 읽으면(예전 PDF/PPTX 경로처럼 extract 한 번, extractUnits 한 번) 같은 파일에서
 * 서로 다른 내용을 읽을 여지가 생긴다 — 파서 옵션이 갈리거나 한쪽만 상한에 걸리는 경우다.
 * 새 형식(IPYNB·HWP·HWPX)은 여기서 한 번만 읽고 두 표현을 만든다.
 *
 * @param fullText  extracted_text에 그대로 들어갈 문자열. 단위 머리글을 포함한다.
 * @param units     material_text_units가 될 단위. 비어 있으면 호출부가 블록으로 나눈다.
 * @param pageCount 파일에서 확인한 물리 페이지 수. 확인할 수 없으면 null(노트북 셀 수·문단 수를
 *                  페이지 수인 것처럼 넣지 않는다).
 * @param warnings  읽지 못했거나 잘라낸 사실. 성공이어도 남는다 — 사용자에게 "일부만 읽었다"를
 *                  알리는 근거이고, 비어 있으면 전부 읽었다는 뜻이다.
 */
public record ExtractedDocument(String fullText, List<ExtractedUnit> units, Integer pageCount,
                                List<String> warnings) {

    public record ExtractedUnit(TextUnitType type, int unitNo, String text) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasText() {
        return fullText != null && !fullText.isBlank();
    }

    public static final class Builder {
        private final List<ExtractedUnit> units = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final StringBuilder fullText = new StringBuilder();
        private Integer pageCount;

        /** 단위 하나를 더한다. 전체 텍스트에는 머리글과 함께 이어 붙인다. */
        public Builder unit(TextUnitType type, int unitNo, String heading, String text) {
            units.add(new ExtractedUnit(type, unitNo, text));
            if (heading != null && !heading.isBlank()) {
                fullText.append(heading).append('\n');
            }
            fullText.append(text.stripTrailing()).append("\n\n");
            return this;
        }

        public Builder warn(String warning) {
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
            return this;
        }

        public Builder pageCount(Integer pageCount) {
            this.pageCount = pageCount;
            return this;
        }

        public boolean isEmpty() {
            return units.isEmpty();
        }

        public int textLength() {
            return fullText.length();
        }

        public ExtractedDocument build() {
            return new ExtractedDocument(fullText.toString(), List.copyOf(units), pageCount, List.copyOf(warnings));
        }
    }
}
