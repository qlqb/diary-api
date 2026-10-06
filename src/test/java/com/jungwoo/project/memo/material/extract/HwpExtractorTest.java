package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HWP 5.x 추출. 파일은 hwplib로 합성하고 파싱도 실제 hwplib가 한다.
 *
 * <p>진짜 한컴 파일로 하는 확인은 저장소에 남의 문서를 넣지 않기 위해 수동 검증으로 돌린다
 * (docs/reviews 기록 참고). 여기서 잡으려는 것은 형식 판별과 단위·표 처리다.
 */
class HwpExtractorTest {

    private final HwpExtractor extractor = new HwpExtractor();

    static Path hwp(Path dir, String name, List<String> paragraphs) throws Exception {
        HWPFile file = BlankFileMaker.make();
        Section section = file.getBodyText().getSectionList().get(0);
        boolean first = true;
        for (String text : paragraphs) {
            Paragraph paragraph = first ? section.getParagraph(0) : section.addNewParagraph();
            first = false;
            if (paragraph.getText() == null) {
                paragraph.createText();
            }
            paragraph.getText().addString(text);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HWPWriter.toStream(file, out);
        Path path = dir.resolve(name);
        Files.write(path, out.toByteArray());
        return path;
    }

    @Test
    void 본문을_구간_단위로_읽는다(@TempDir Path dir) throws Exception {
        Path file = hwp(dir, "syllabus.hwp",
                List.of("자료구조 강의계획서", "3주차 연결 리스트 실습", "제출은 9월 25일까지"));

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.fullText()).contains("자료구조 강의계획서").contains("제출은 9월 25일까지");
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::type)
                .containsOnly(TextUnitType.TEXT_BLOCK);
        // 쪽수는 파일에 없다 — 문단 수를 쪽수라고 적지 않는다.
        assertThat(document.pageCount()).isNull();
    }

    /**
     * 표 걷기. 파일로 쓰지 않고 hwplib 객체 모델을 그대로 넣는다 — hwplib의 쓰기 경로는 표 있는 문서를
     * 만들 때 셀 서식까지 요구해서, 그걸 맞추다 보면 정작 검증하려는 "행·셀 관계가 남는가"가 흐려진다.
     */
    @Test
    void 표는_행과_셀_관계가_남게_옮긴다() throws Exception {
        HWPFile file = BlankFileMaker.make();
        Section section = file.getBodyText().getSectionList().get(0);
        Paragraph paragraph = section.getParagraph(0);
        if (paragraph.getText() == null) {
            paragraph.createText();
        }
        paragraph.getText().addString("주차별 계획");
        ControlTable control = (ControlTable) paragraph.addNewControl(ControlType.Table);
        for (List<String> rowText : List.of(List.of("주차", "제출일"), List.of("3주차", "9월 25일"))) {
            Row row = control.addNewRow();
            for (String cellText : rowText) {
                Cell cell = row.addNewCell();
                Paragraph cellParagraph = cell.getParagraphList().addNewParagraph();
                cellParagraph.createText();
                cellParagraph.getText().addString(cellText);
            }
        }

        ExtractedDocument document = extractor.extractFrom(file);

        assertThat(document.fullText())
                .contains("주차별 계획")
                .contains("[표 시작]")
                .contains("| 주차 | 제출일 |")
                .contains("| 3주차 | 9월 25일 |")
                .contains("[표 끝]");
    }

    @Test
    void HWP가_아닌_파일은_이유와_함께_실패한다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fake.hwp");
        Files.writeString(file, "HWP Document File V3.00");

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("HWP 5.0")
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.CORRUPTED);
    }

    @Test
    void 글자가_없으면_빈_결과다(@TempDir Path dir) throws Exception {
        Path file = hwp(dir, "empty.hwp", List.of(" "));

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.hasText()).isFalse();
    }
}
