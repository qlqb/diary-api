package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 텍스트 기반 PDF/PPTX/HWP/IPYNB/ZIP 추출이 실제로 동작하는지, 텍스트가 없는 PDF는 OCR 없이 명확하게
 * FAILED_NO_TEXT로 실패 처리되는지 검증한다. 실제 PDFBox/POI/hwplib 라이브러리로 문서를 만들고
 * 그대로 추출한다 — 라이브러리 자체를 목(mock)하지 않는다.
 */
class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extract_readsTextFromRealPdf(@TempDir Path tempDir) throws IOException {
        Path pdf = tempDir.resolve("syllabus.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("3.2 Simple Linked List");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        TextExtractionService.ExtractionResult result = service.extract(pdf, "pdf");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.text()).contains("Simple Linked List");
    }

    @Test
    void extract_readsTextFromRealPptx(@TempDir Path tempDir) throws IOException {
        Path pptx = tempDir.resolve("slides.pptx");
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText("Sentinel Node");
            try (OutputStream out = Files.newOutputStream(pptx)) {
                slideShow.write(out);
            }
        }

        TextExtractionService.ExtractionResult result = service.extract(pptx, "pptx");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.text()).contains("Sentinel Node");
    }

    @Test
    void extract_returnsFailedNoText_whenPdfHasNoTextLayer(@TempDir Path tempDir) throws IOException {
        Path pdf = tempDir.resolve("scanned.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage()); // 텍스트 레이어 없이 빈 페이지 — 스캔 이미지뿐인 상황을 흉내
            document.save(pdf.toFile());
        }

        TextExtractionService.ExtractionResult result = service.extract(pdf, "pdf");

        assertThat(result.status()).isEqualTo(ExtractionStatus.FAILED_NO_TEXT);
        assertThat(result.text()).isNull();
    }

    @Test
    void extract_readsTextFromRealHwp(@TempDir Path tempDir) throws Exception {
        Path hwp = tempDir.resolve("syllabus.hwp");
        Files.write(hwp, hwpBytes("자료구조 강의계획서 3주차 연결 리스트"));

        TextExtractionService.ExtractionResult result = service.extract(hwp, "hwp");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.text()).contains("자료구조 강의계획서 3주차 연결 리스트");
    }

    @Test
    void extract_hwpThatIsNotHwp_fails(@TempDir Path tempDir) throws IOException {
        Path hwp = tempDir.resolve("broken.hwp");
        Files.writeString(hwp, "not a hwp document");

        assertThat(service.extract(hwp, "hwp").status()).isEqualTo(ExtractionStatus.FAILED);
    }

    @Test
    void extract_readsNotebookCellSources_butNotOutputs(@TempDir Path tempDir) throws IOException {
        Path ipynb = tempDir.resolve("lab.ipynb");
        Files.writeString(ipynb, NOTEBOOK, StandardCharsets.UTF_8);

        TextExtractionService.ExtractionResult result = service.extract(ipynb, "ipynb");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.text())
                .contains("--- 셀 1 · 설명 ---\n# 실습 3: 선형 회귀\n최소제곱법으로")
                .contains("--- 셀 2 · 코드 ---\n```python\nimport numpy as np\nx = np.arange(10)\n```")
                .doesNotContain("iVBORw0KGgo")      // 출력의 base64 이미지
                .doesNotContain("[0 1 2")           // 출력의 stream 텍스트
                .doesNotContain("셀 3");            // 빈 셀은 건너뛴다
    }

    @Test
    void extract_notebookWithOnlyEmptyCells_isNoText(@TempDir Path tempDir) throws IOException {
        Path ipynb = tempDir.resolve("empty.ipynb");
        Files.writeString(ipynb, "{\"cells\": [{\"cell_type\": \"code\", \"source\": []}], \"nbformat\": 4}");

        assertThat(service.extract(ipynb, "ipynb").status()).isEqualTo(ExtractionStatus.FAILED_NO_TEXT);
    }

    /**
     * Windows 탐색기가 만든 zip처럼 이름을 CP949로(UTF-8 플래그 없이) 쓴다. 문서 형식과 소스·텍스트 파일은
     * 읽고, 이미지·중첩 압축은 목록에만 남기며, macOS 메타데이터는 아예 빼는지 본다.
     */
    @Test
    void extract_zip_readsSupportedEntriesInNameOrder_withCp949Names(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("week3.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), Charset.forName("MS949"))) {
            entry(out, "3주차/실습.ipynb", NOTEBOOK.getBytes(StandardCharsets.UTF_8));
            entry(out, "3주차/강의계획서.hwp", hwpBytes("평가 비율 중간 30"));
            entry(out, "3주차/slides.pptx", pptxBytes("Sentinel Node"));
            entry(out, "3주차/main.py", "print('hello')".getBytes(StandardCharsets.UTF_8));
            entry(out, "3주차/메모.txt", "과제 제출은 금요일".getBytes(Charset.forName("MS949")));
            entry(out, "3주차/diagram.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            entry(out, "3주차/old.zip", new byte[]{'P', 'K', 5, 6});
            entry(out, "__MACOSX/3주차/._실습.ipynb", new byte[]{0});
        }

        TextExtractionService.ExtractionResult result = service.extract(zip, "zip");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        String text = result.text();
        assertThat(text)
                .contains("===== 파일: 3주차/강의계획서.hwp =====\n평가 비율 중간 30")
                .contains("===== 파일: 3주차/실습.ipynb =====\n--- 셀 1 · 설명 ---")
                .contains("===== 파일: 3주차/main.py =====\nprint('hello')")
                .contains("===== 파일: 3주차/메모.txt =====\n과제 제출은 금요일")
                // zip 안의 슬라이드 표식은 괄호가 아니다 — 자동 분석의 [구간 N]과 섞이지 않게.
                .contains("(슬라이드 1)\nSentinel Node")
                .doesNotContain("[슬라이드")
                .contains("읽은 문서 5개 · 읽지 않은 파일 2개")
                .contains("- 3주차/diagram.png")
                .contains("- 3주차/old.zip")
                .doesNotContain("__MACOSX");
        // 이름순(유니코드): main.py < slides.pptx < 강의계획서.hwp < 메모.txt < 실습.ipynb
        assertThat(text.indexOf("파일: 3주차/main.py")).isLessThan(text.indexOf("파일: 3주차/slides.pptx"));
        assertThat(text.indexOf("파일: 3주차/slides.pptx")).isLessThan(text.indexOf("파일: 3주차/강의계획서.hwp"));
        assertThat(text.indexOf("파일: 3주차/메모.txt")).isLessThan(text.indexOf("파일: 3주차/실습.ipynb"));
    }

    @Test
    void extract_zip_skipsEntryLargerThanTheLimit_withoutTrustingTheHeaderSize(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("bomb.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("a/huge.txt"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i <= TextExtractionService.ZIP_MAX_ENTRY_BYTES / zeros.length; i++) {
                out.write(zeros);
            }
            out.closeEntry();
            entry(out, "b/readme.md", "# 과제 안내".getBytes(StandardCharsets.UTF_8));
        }

        TextExtractionService.ExtractionResult result = service.extract(zip, "zip");

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.text()).contains("# 과제 안내").contains("- a/huge.txt (너무 큼)");
    }

    @Test
    void extract_zipWithoutReadableDocuments_isNoText(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("images.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(out, "a.png", new byte[]{1, 2, 3});
            entry(out, "broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
        }

        TextExtractionService.ExtractionResult result = service.extract(zip, "zip");

        assertThat(result.status()).isEqualTo(ExtractionStatus.FAILED_NO_TEXT);
        assertThat(result.error()).contains("압축 파일 안에서 읽을 수 있는 문서를 찾지 못했습니다");
    }

    @Test
    void decodeText_fallsBackToMs949() {
        assertThat(TextExtractionService.decodeText("한글".getBytes(StandardCharsets.UTF_8))).isEqualTo("한글");
        assertThat(TextExtractionService.decodeText("한글".getBytes(Charset.forName("MS949")))).isEqualTo("한글");
    }

    static final String NOTEBOOK = """
            {
             "cells": [
              {"cell_type": "markdown", "metadata": {}, "source": ["# 실습 3: 선형 회귀\\n", "최소제곱법으로 직선을 맞춘다."]},
              {"cell_type": "code", "execution_count": 1, "metadata": {},
               "outputs": [
                {"name": "stdout", "output_type": "stream", "text": ["[0 1 2 3 4 5 6 7 8 9]\\n"]},
                {"data": {"image/png": "iVBORw0KGgoAAAANSUhEUg=="}, "output_type": "display_data", "metadata": {}}
               ],
               "source": ["import numpy as np\\n", "x = np.arange(10)"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": []}
             ],
             "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}},
             "nbformat": 4, "nbformat_minor": 5
            }
            """;

    /** hwplib의 빈 문서 첫 문단에 글자를 넣어 HWP 5.0 바이트로 쓴다. */
    static byte[] hwpBytes(String text) throws Exception {
        HWPFile hwp = BlankFileMaker.make();
        Paragraph paragraph = hwp.getBodyText().getSectionList().get(0).getParagraph(0);
        if (paragraph.getText() == null) {
            paragraph.createText();
        }
        paragraph.getText().addString(text);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HWPWriter.toStream(hwp, out);
        return out.toByteArray();
    }

    static byte[] pptxBytes(String text) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            slideShow.createSlide().createTextBox().setText(text);
            slideShow.write(out);
            return out.toByteArray();
        }
    }

    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
