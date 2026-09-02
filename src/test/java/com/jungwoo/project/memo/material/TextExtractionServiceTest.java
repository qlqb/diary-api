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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 텍스트 기반 PDF/PPTX 추출이 실제로 동작하는지, 텍스트가 없는 PDF는 OCR 없이 명확하게
 * FAILED_NO_TEXT로 실패 처리되는지 검증한다. 실제 PDFBox/POI 라이브러리로 문서를 만들고
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
}
