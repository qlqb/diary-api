package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.analysis.MaterialChunker;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.TextUnitType;
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
import org.mockito.Mockito;

import java.awt.Rectangle;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합성 문서로 단위 추출을 검증한다. 사용자 실제 파일은 쓰지 않는다.
 *
 * <p>표 6("긴 자료의 마지막 부분에만 문제/과제")의 결정적 절반: 마지막 페이지가 단위로 남고, 청크가
 * 그 단위를 반드시 포함한다. 모델이 그 청크를 읽는지는 실호출 검증이 본다.
 */
class MaterialTextUnitServiceTest {

    private final MaterialTextUnitService service = new MaterialTextUnitService(
            Mockito.mock(MaterialTextUnitMapper.class), Mockito.mock(CourseMaterialMapper.class),
            Mockito.mock(FileStorageService.class));

    /** 페이지마다 영문 채움 텍스트(PDFBox 표준 폰트는 한글을 못 그린다). 마지막 페이지에만 제출 단서. */
    static Path syntheticLongPdf(Path dir, int pages, int linesPerPage) throws Exception {
        Path file = dir.resolve("synthetic-" + pages + "p.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.setLeading(12f);
                    cs.newLineAtOffset(40, 740);
                    cs.showText("Page " + p + " of " + pages + " - Chapter " + ((p - 1) / 5 + 1));
                    cs.newLine();
                    for (int l = 0; l < linesPerPage; l++) {
                        cs.showText("Lecture note line " + l + " about linked list operations, insertion and deletion. "
                                + "Filler text to make the page long enough for chunking tests.");
                        cs.newLine();
                    }
                    if (p == pages) {
                        cs.showText("EXERCISE 3: Implement delete at head and middle. SUBMIT screenshot and code by 9/18 (due).");
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                document.save(out);
            }
        }
        return file;
    }

    @Test
    void pdfIsSplitPerPage_andTheLastPageWithTheSubmissionCueIsKept(@TempDir Path dir) throws Exception {
        Path pdf = syntheticLongPdf(dir, 40, 45);

        MaterialTextUnitService.Extracted extracted = service.extractUnits(pdf, "pdf", 1L, 7L, "hash");

        assertThat(extracted.pageCount()).isEqualTo(40);
        assertThat(extracted.units()).hasSize(40);
        assertThat(extracted.units().get(0).getUnitType()).isEqualTo(TextUnitType.PDF_PAGE);
        assertThat(extracted.units().get(0).getUnitNo()).isEqualTo(1);
        MaterialTextUnit last = extracted.units().get(39);
        assertThat(last.getUnitNo()).isEqualTo(40);
        assertThat(last.getText()).contains("SUBMIT");
        assertThat(last.getCharCount()).isEqualTo(last.getText().length());

        // 전체 길이가 한 청크 예산을 확실히 넘는다(24,000자). 그래도 마지막 페이지는 어느 청크엔가 있다.
        int total = extracted.units().stream().mapToInt(MaterialTextUnit::getCharCount).sum();
        assertThat(total).isGreaterThan(24_000 * 2);
        List<MaterialChunker.Chunk> chunks = MaterialChunker.split(extracted.units(), 24_000, 2_000);
        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(chunks.get(chunks.size() - 1).lastUnitNo()).isEqualTo(40);
        assertThat(chunks.get(chunks.size() - 1).units()).anyMatch(u -> u.getText().contains("SUBMIT"));
    }

    @Test
    void pptxIsSplitPerSlide_andEmptySlidesAreSkipped(@TempDir Path dir) throws Exception {
        Path pptx = dir.resolve("deck.pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            for (int i = 1; i <= 3; i++) {
                XSLFSlide slide = show.createSlide();
                if (i == 2) {
                    continue; // 그림만 있는 슬라이드처럼 텍스트가 없다.
                }
                XSLFTextBox box = slide.createTextBox();
                box.setAnchor(new Rectangle(50, 50, 400, 100));
                box.setText("Slide " + i + " content about stacks and queues");
            }
            try (OutputStream out = Files.newOutputStream(pptx)) {
                show.write(out);
            }
        }

        MaterialTextUnitService.Extracted extracted = service.extractUnits(pptx, "pptx", 1L, 8L, "hash");

        assertThat(extracted.pageCount()).isEqualTo(3);
        assertThat(extracted.units()).extracting(MaterialTextUnit::getUnitNo).containsExactly(1, 3);
        assertThat(extracted.units()).allMatch(u -> u.getUnitType() == TextUnitType.PPTX_SLIDE);
    }

    @Test
    void textWithoutAFile_becomesNumberedBlocks_notPages() {
        String text = ("paragraph line\n").repeat(1_500); // ~22,500자 → 6,000자 블록 4개
        List<MaterialTextUnit> units = service.blocksFromText(text, 1L, 9L, "hash");

        assertThat(units.size()).isBetween(4, 5);
        assertThat(units).allMatch(u -> u.getUnitType() == TextUnitType.TEXT_BLOCK);
        assertThat(units.get(0).getUnitNo()).isEqualTo(1);
        assertThat(units.stream().mapToInt(MaterialTextUnit::getCharCount).sum()).isEqualTo(text.length());
    }

    @Test
    void unsupportedExtension_yieldsNoUnits(@TempDir Path dir) throws Exception {
        Path txt = Files.writeString(dir.resolve("a.txt"), "hello");
        assertThat(service.extractUnits(txt, "txt", 1L, 1L, "h").units()).isEmpty();
    }
}
