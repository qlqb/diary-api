package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 노트북 추출. 여기서 지키려는 것은 "셀 번호가 원본 그대로 남는가"다 — 구간이 "셀 8~12"라고
 * 말하려면 번호가 밀리면 안 되고, 그 번호는 실행 순서(execution_count)가 아니라 문서 순서다.
 */
class NotebookExtractorTest {

    private final NotebookExtractor extractor = new NotebookExtractor();

    private ExtractedDocument extract(String json) {
        return extractor.extract(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String notebook(String cells) {
        return """
                {"cells": [%s],
                 "metadata": {"kernelspec": {"language": "python", "name": "python3"}},
                 "nbformat": 4, "nbformat_minor": 5}
                """.formatted(cells);
    }

    @Test
    void 설명과_코드를_순서대로_읽고_셀_번호는_원본_그대로다() {
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "markdown", "source": ["# 실습 3\\n", "연결 리스트를 구현한다."]},
                {"cell_type": "code", "execution_count": 7, "outputs": [], "source": []},
                {"cell_type": "code", "execution_count": 2, "outputs": [],
                 "source": "def push(x):\\n    return x"}
                """));

        // 2번 셀은 비어 건너뛰지만 그 뒤 셀은 3번 그대로다. execution_count(7, 2)는 위치가 아니다.
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::unitNo)
                .containsExactly(1, 3);
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::type)
                .containsOnly(TextUnitType.NOTEBOOK_CELL);
        assertThat(document.units().get(0).text()).contains("# 실습 3").contains("연결 리스트를 구현한다.");
        // 코드는 개행·들여쓰기가 의미다. 울타리 안에 원문 그대로 둔다.
        assertThat(document.units().get(1).text()).contains("```python").contains("def push(x):\n    return x");
        assertThat(document.pageCount()).isNull();
        assertThat(document.fullText()).contains("--- 셀 1 · 설명 ---").contains("--- 셀 3 · 코드 ---");
    }

    @Test
    void 출력은_텍스트만_상한_안에서_싣고_이미지는_종류만_남긴다() {
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "code", "execution_count": 1, "source": "plot()",
                 "outputs": [
                   {"output_type": "stream", "name": "stdout", "text": ["정확도 0.93\\n"]},
                   {"output_type": "display_data", "metadata": {},
                    "data": {"image/png": "iVBORw0KGgoAAAANSUhEUgAAAAUA"}},
                   {"output_type": "error", "ename": "ValueError", "evalue": "shape mismatch", "traceback": []}
                 ]}
                """));

        String text = document.units().get(0).text();
        assertThat(text).contains("정확도 0.93").contains("ValueError: shape mismatch");
        assertThat(text).doesNotContain("iVBORw0KGgo");
        assertThat(text).contains("텍스트가 아니라 싣지 않았어요");
    }

    @Test
    void 아주_긴_출력은_잘라내고_잘랐다는_사실을_남긴다() {
        String long_ = "가".repeat(ExtractionLimits.MAX_OUTPUT_CHARS + 500);
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "code", "source": "print(x)",
                 "outputs": [{"output_type": "stream", "name": "stdout", "text": "%s"}]}
                """.formatted(long_)));

        assertThat(document.warnings()).anyMatch(w -> w.contains("실행 결과가 길어"));
        assertThat(document.units().get(0).text()).contains("[실행 결과 · 일부]");
    }

    @Test
    void 아주_긴_셀은_나뉘어도_셀_번호가_같다() {
        String long_ = "x".repeat(ExtractionLimits.MAX_UNIT_CHARS * 2 + 100);
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "markdown", "source": "%s"}
                """.formatted(long_)));

        assertThat(document.units()).hasSizeGreaterThan(1);
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::unitNo).containsOnly(1);
        assertThat(document.fullText()).contains("--- 셀 1 · 설명 (이어서) ---");
    }

    @Test
    void nbformat_4가_아니면_지원하지_않는_판으로_실패한다() {
        String v3 = """
                {"nbformat": 3, "nbformat_minor": 0,
                 "worksheets": [{"cells": [{"cell_type": "code", "input": ["print(1)"]}]}]}
                """;

        assertThatThrownBy(() -> extract(v3))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("nbformat 3")
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void 노트북이_아닌_JSON은_손상으로_실패한다() {
        assertThatThrownBy(() -> extract("{\"hello\": \"world\"}"))
                .isInstanceOf(DocumentExtractionException.class)
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.CORRUPTED);

        assertThatThrownBy(() -> extract("이건 JSON이 아니다"))
                .isInstanceOf(DocumentExtractionException.class)
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.CORRUPTED);
    }

    @Test
    void HTML_출력이_있어도_원문_그대로_다루고_실행하지_않는다() {
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "code", "source": "display(HTML(x))",
                 "outputs": [{"output_type": "display_data", "metadata": {},
                              "data": {"text/html": "<script>alert(1)</script>",
                                       "text/plain": "<IPython.core.display.HTML object>"}}]}
                """));

        // text/plain만 싣는다. HTML·스크립트는 본문에 들어가지 않는다.
        assertThat(document.units().get(0).text())
                .contains("<IPython.core.display.HTML object>")
                .doesNotContain("<script>");
    }

    @Test
    void 셀이_전부_비면_텍스트가_없다() {
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "code", "source": []}, {"cell_type": "markdown", "source": ""}
                """));

        assertThat(document.hasText()).isFalse();
        assertThat(document.units()).isEmpty();
    }

    @Test
    void source가_문자열이든_배열이든_같게_읽는다() {
        ExtractedDocument asArray = extract(notebook(
                """
                {"cell_type": "markdown", "source": ["첫 줄\\n", "둘째 줄"]}
                """));
        ExtractedDocument asString = extract(notebook(
                """
                {"cell_type": "markdown", "source": "첫 줄\\n둘째 줄"}
                """));

        assertThat(asArray.units().get(0).text()).isEqualTo(asString.units().get(0).text());
        assertThat(asArray.units().get(0).text()).isEqualTo("첫 줄\n둘째 줄\n");
    }

    /**
     * 수업 노트북에 흔한 모양: 화면 캡처를 붙여 넣으면 Jupyter가 마크다운 원문에 base64로 통째로 넣는다.
     * 한 장이 수십만 자라, 글자로 읽으면 본문이 base64로 덮이고 자료 저장(DB 패킷 한도)까지 실패했다.
     */
    @Test
    void 마크다운에_붙여_넣은_그림은_자리만_남기고_base64는_읽지_않는다() {
        String image = "iVBORw0KGgo" + "A".repeat(200_000);
        ExtractedDocument document = extract(notebook("""
                {"cell_type": "markdown",
                 "source": ["## 리스트 슬라이싱\\n", "![image.png](data:image/png;base64,%s)\\n", "끝 인덱스는 포함하지 않는다."]},
                {"cell_type": "markdown",
                 "source": "<img src=\\"data:image/jpeg;base64,%s\\" width=300> 설명 그림"}
                """.formatted(image, image)));

        String first = document.units().get(0).text();
        assertThat(first).contains("## 리스트 슬라이싱").contains("[그림: image.png]")
                .contains("끝 인덱스는 포함하지 않는다.");
        assertThat(document.units().get(1).text()).contains("[그림 데이터 생략]").contains("설명 그림");
        assertThat(document.fullText()).doesNotContain("iVBORw0KGgo");
        // 셀 하나가 셀 하나로 남는다 — base64 때문에 여러 단위로 쪼개지지 않는다.
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::unitNo).containsExactly(1, 2);
        assertThat(document.fullText().length()).isLessThan(500);
    }
}
