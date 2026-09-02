package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 텍스트 기반 PDF / PPTX에서 텍스트를 추출한다. 스캔 이미지뿐인 PDF(텍스트 레이어 없음)는
 * OCR을 하지 않고 FAILED_NO_TEXT로 명확히 실패 처리한다.
 */
@Slf4j
@Component
public class TextExtractionService {

    public record ExtractionResult(ExtractionStatus status, String text, String error) {
        static ExtractionResult success(String text) {
            return new ExtractionResult(ExtractionStatus.SUCCESS, text, null);
        }

        static ExtractionResult noText() {
            return new ExtractionResult(ExtractionStatus.FAILED_NO_TEXT, null,
                    "문서에서 텍스트를 추출하지 못했습니다 (스캔 이미지이거나 텍스트 레이어가 없을 수 있습니다)");
        }

        static ExtractionResult failed(String message) {
            return new ExtractionResult(ExtractionStatus.FAILED, null, message);
        }
    }

    public ExtractionResult extract(Path filePath, String extension) {
        try {
            String text = switch (extension.toLowerCase()) {
                case "pdf" -> extractPdf(filePath);
                case "pptx" -> extractPptx(filePath);
                default -> null;
            };
            if (text == null || text.isBlank()) {
                return ExtractionResult.noText();
            }
            return ExtractionResult.success(text);
        } catch (Exception e) {
            log.warn("텍스트 추출 실패: filePath={}", filePath, e);
            return ExtractionResult.failed("텍스트 추출 중 오류가 발생했습니다: " + e.getClass().getSimpleName());
        }
    }

    private String extractPdf(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractPptx(Path filePath) throws IOException {
        try (InputStream in = Files.newInputStream(filePath);
             XMLSlideShow slideShow = new XMLSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            int slideNumber = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                slideNumber++;
                sb.append("[슬라이드 ").append(slideNumber).append("]\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}
