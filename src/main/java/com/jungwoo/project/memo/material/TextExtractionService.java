package com.jungwoo.project.memo.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 자료 파일에서 텍스트를 추출한다. 형식은 {@link MaterialFileFormat}과 같다.
 *
 * <ul>
 *   <li>PDF·PPTX: 텍스트 레이어·텍스트 상자. 스캔 이미지뿐인 PDF는 OCR을 하지 않고 FAILED_NO_TEXT다.</li>
 *   <li>HWP: HWP 5.0 본문(표·글상자 안 글자 포함). 암호가 걸린 문서는 FAILED다.</li>
 *   <li>IPYNB: 마크다운·코드 셀의 원문. 실행 결과(출력)는 읽지 않는다 — 이미지가 base64로 들어 있어
 *       분량만 키우고, 학습 구조는 셀 원문에 있다.</li>
 *   <li>ZIP: 안에 든 위 형식과 텍스트·소스 파일을 이름순으로 읽어 파일 머리글과 함께 잇는다. 압축 안의
 *       압축은 풀지 않는다. 항목 수·해제 크기·글자 수에 상한을 둔다(압축 폭탄).</li>
 * </ul>
 */
@Slf4j
@Component
public class TextExtractionService {

    /** zip 안에서 읽을 문서 수 상한. 넘으면 그 뒤는 목록에만 남긴다. */
    static final int ZIP_MAX_DOCUMENTS = 100;
    /** zip 항목 하나를 풀어 담는 상한(바이트). 헤더의 크기 값은 믿지 않고 실제로 읽으며 센다. */
    static final int ZIP_MAX_ENTRY_BYTES = 30 * 1024 * 1024;
    /** zip 전체에서 풀어 읽는 바이트 상한. */
    static final long ZIP_MAX_TOTAL_BYTES = 150L * 1024 * 1024;
    /** zip에서 모으는 텍스트 상한. 자동 분석은 단위 수에 비례해 모델을 부른다. */
    static final int ZIP_MAX_TEXT_CHARS = 1_000_000;
    /** 목록 요약에 이름을 적는 건너뛴 파일 수. */
    private static final int ZIP_MAX_LISTED_SKIPS = 30;

    /** zip 안에서 문서로 읽는 텍스트·소스 파일 확장자. 데이터 파일(csv·json)은 분량만 크고 구조가 없어 뺀다. */
    static final Set<String> ZIP_PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "md", "py", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx", "kt", "go",
            "rs", "rb", "php", "swift", "r", "m", "sql", "html", "css", "sh", "yml", "yaml", "xml");

    private static final Charset MS949 = Charset.forName("MS949");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LEADING_BLANK_LINES = Pattern.compile("\\A(?:[ \\t]*\\R)+");

    public record ExtractionResult(ExtractionStatus status, String text, String error) {
        static ExtractionResult success(String text) {
            return new ExtractionResult(ExtractionStatus.SUCCESS, text, null);
        }

        static ExtractionResult noText(String message) {
            return new ExtractionResult(ExtractionStatus.FAILED_NO_TEXT, null, message);
        }

        static ExtractionResult failed(String message) {
            return new ExtractionResult(ExtractionStatus.FAILED, null, message);
        }
    }

    public ExtractionResult extract(Path filePath, String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        try {
            String text = switch (ext) {
                case "pdf" -> extractPdf(Loader.loadPDF(filePath.toFile()));
                case "pptx" -> {
                    try (InputStream in = Files.newInputStream(filePath)) {
                        yield extractPptx(in, SlideMarker.BRACKET);
                    }
                }
                case "hwp" -> {
                    try (InputStream in = Files.newInputStream(filePath)) {
                        yield extractHwp(in);
                    }
                }
                case "ipynb" -> extractNotebook(Files.readAllBytes(filePath));
                case "zip" -> extractZip(filePath);
                default -> null;
            };
            if (text == null || text.isBlank()) {
                return ExtractionResult.noText(noTextMessage(ext));
            }
            return ExtractionResult.success(text);
        } catch (Exception e) {
            log.warn("텍스트 추출 실패: filePath={}", filePath, e);
            return ExtractionResult.failed("텍스트 추출 중 오류가 발생했습니다: " + e.getClass().getSimpleName());
        }
    }

    private static String noTextMessage(String ext) {
        return switch (ext) {
            case "pdf" -> "문서에서 텍스트를 추출하지 못했습니다 (스캔 이미지이거나 텍스트 레이어가 없을 수 있습니다)";
            case "zip" -> "압축 파일 안에서 읽을 수 있는 문서를 찾지 못했습니다 "
                    + "(PDF·PPTX·HWP·IPYNB와 텍스트·소스 파일만 읽습니다)";
            case "ipynb" -> "노트북에 내용이 있는 셀이 없습니다";
            default -> "문서에서 텍스트를 추출하지 못했습니다";
        };
    }

    // ===== 형식별 =====

    private String extractPdf(PDDocument loaded) throws IOException {
        try (PDDocument document = loaded) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * 슬라이드 표식. 단독 PPTX는 {@code [슬라이드 N]}을 쓴다(링크 제안이 이 표식으로 앞 5장을 자른다).
     * zip 안에서는 괄호를 쓰지 않는다 — 자동 분석이 단위마다 {@code [구간 N]}을 붙이는데, 본문에 같은 모양의
     * {@code [슬라이드 N]}이 섞이면 모델이 그 번호를 단위 번호로 착각할 수 있다.
     */
    enum SlideMarker {
        BRACKET, PLAIN
    }

    private String extractPptx(InputStream in, SlideMarker marker) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            int slideNumber = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                slideNumber++;
                sb.append(marker == SlideMarker.BRACKET ? "[슬라이드 " + slideNumber + "]" : "(슬라이드 " + slideNumber + ")")
                        .append('\n');
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

    private String extractHwp(InputStream in) throws Exception {
        HWPFile hwp = HWPReader.fromInputStream(in);
        if (hwp == null) {
            throw new IOException("HWP 5.0 문서가 아닙니다");
        }
        // 표·글상자 안의 글자를 문단 사이 제자리에 넣는다. 강의계획서의 주차표가 대개 표다.
        return TextExtractor.extract(hwp, TextExtractMethod.InsertControlTextBetweenParagraphText);
    }

    /** nbformat 4(cells)와 3(worksheets[].cells, 코드는 input)을 읽는다. */
    String extractNotebook(byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        JsonNode cells = root.path("cells");
        if (!cells.isArray()) {
            cells = root.path("worksheets").path(0).path("cells");
        }
        String language = root.path("metadata").path("kernelspec").path("language").asText(
                root.path("metadata").path("language_info").path("name").asText(""));
        StringBuilder sb = new StringBuilder();
        int cellNo = 0;
        for (JsonNode cell : cells) {
            cellNo++;
            String type = cell.path("cell_type").asText("");
            String source = joinSource(cell.has("source") ? cell.get("source") : cell.path("input"));
            if (source.isBlank()) {
                continue;
            }
            // 셀 표식도 괄호를 쓰지 않는다(SlideMarker와 같은 이유).
            if ("code".equals(type)) {
                sb.append("--- 셀 ").append(cellNo).append(" · 코드 ---\n")
                        .append("```").append(language).append('\n')
                        .append(source.stripTrailing()).append("\n```\n\n");
            } else {
                sb.append("--- 셀 ").append(cellNo).append(" · 설명 ---\n")
                        .append(source.stripTrailing()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String joinSource(JsonNode source) {
        if (source.isArray()) {
            StringBuilder sb = new StringBuilder();
            source.forEach(line -> sb.append(line.asText("")));
            return sb.toString();
        }
        return source.asText("");
    }

    // ===== ZIP =====

    private String extractZip(Path filePath) throws IOException {
        try (ZipFile zip = openZip(filePath)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                    .filter(e -> !e.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            StringBuilder body = new StringBuilder();
            List<String> skipped = new ArrayList<>();
            int documents = 0;
            long totalBytes = 0;
            String stopReason = null;

            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (isNoise(name)) {
                    continue;
                }
                String ext = extensionOf(name);
                boolean readable = MaterialFileFormat.fromExtension(ext).filter(f -> f != MaterialFileFormat.ZIP).isPresent()
                        || ZIP_PLAIN_TEXT_EXTENSIONS.contains(ext);
                if (!readable) {
                    skipped.add(name);
                    continue;
                }
                if (stopReason != null) {
                    skipped.add(name);
                    continue;
                }
                if (documents >= ZIP_MAX_DOCUMENTS) {
                    stopReason = "문서가 " + ZIP_MAX_DOCUMENTS + "개를 넘어";
                    skipped.add(name);
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes(ZIP_MAX_ENTRY_BYTES + 1);
                } catch (ZipException e) {
                    // 암호가 걸린 항목, 지원하지 않는 압축 방식.
                    skipped.add(name + " (열 수 없음)");
                    continue;
                }
                if (bytes.length > ZIP_MAX_ENTRY_BYTES) {
                    skipped.add(name + " (너무 큼)");
                    continue;
                }
                totalBytes += bytes.length;
                if (totalBytes > ZIP_MAX_TOTAL_BYTES) {
                    stopReason = "풀어 읽은 크기가 상한을 넘어";
                    skipped.add(name);
                    continue;
                }
                String text = extractInner(name, ext, bytes);
                if (text == null || text.isBlank()) {
                    skipped.add(name + " (글자 없음)");
                    continue;
                }
                documents++;
                body.append("===== 파일: ").append(name).append(" =====\n")
                        // 앞쪽 빈 줄만 걷는다(hwp는 구역 정의 문단이 빈 줄로 나온다). 첫 줄 들여쓰기는 둔다.
                        .append(LEADING_BLANK_LINES.matcher(text).replaceFirst("").stripTrailing()).append("\n\n");
                if (body.length() > ZIP_MAX_TEXT_CHARS) {
                    body.setLength(ZIP_MAX_TEXT_CHARS);
                    body.append("\n");
                    stopReason = "글자 수가 상한을 넘어";
                }
            }
            if (documents == 0) {
                return null;
            }
            return body.append(summary(documents, skipped, stopReason)).toString();
        }
    }

    /**
     * 파일 이름 인코딩: UTF-8 플래그가 있으면 UTF-8로 읽힌다. 플래그 없이 Windows 탐색기·반디집이 만든
     * 한글 이름은 CP949라 UTF-8로 열면 ZipException이 난다 — 그때 MS949로 다시 연다.
     */
    private static ZipFile openZip(Path filePath) throws IOException {
        try {
            return new ZipFile(filePath.toFile(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            return new ZipFile(filePath.toFile(), MS949);
        }
    }

    private String extractInner(String name, String ext, byte[] bytes) {
        try {
            return switch (ext) {
                case "pdf" -> extractPdf(Loader.loadPDF(bytes));
                case "pptx" -> extractPptx(new ByteArrayInputStream(bytes), SlideMarker.PLAIN);
                case "hwp" -> extractHwp(new ByteArrayInputStream(bytes));
                case "ipynb" -> extractNotebook(bytes);
                default -> decodeText(bytes);
            };
        } catch (Exception e) {
            // 한 파일이 깨졌다고 압축 전체를 실패로 만들지 않는다.
            log.info("zip 항목 추출 실패: name={}, type={}", name, e.getClass().getSimpleName());
            return null;
        }
    }

    /** UTF-8로 읽되, 깨지면 MS949(한글 Windows 메모장 기본)로 읽는다. */
    static String decodeText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return new String(bytes, MS949);
        }
    }

    /** macOS가 넣는 메타데이터, 숨김 파일, Office 잠금 파일. 목록에도 남기지 않는다. */
    private static boolean isNoise(String name) {
        if (name.startsWith("__MACOSX/")) {
            return true;
        }
        String base = name.substring(name.lastIndexOf('/') + 1);
        return base.startsWith(".") || base.startsWith("~$") || base.equalsIgnoreCase("Thumbs.db");
    }

    private static String summary(int documents, List<String> skipped, String stopReason) {
        StringBuilder sb = new StringBuilder("===== 압축 파일 요약 =====\n");
        sb.append("읽은 문서 ").append(documents).append("개");
        if (!skipped.isEmpty()) {
            sb.append(" · 읽지 않은 파일 ").append(skipped.size()).append("개");
        }
        sb.append('\n');
        if (stopReason != null) {
            sb.append(stopReason).append(" 이후 파일은 읽지 않았습니다.\n");
        }
        skipped.stream().limit(ZIP_MAX_LISTED_SKIPS).forEach(name -> sb.append("- ").append(name).append('\n'));
        if (skipped.size() > ZIP_MAX_LISTED_SKIPS) {
            sb.append("- 외 ").append(skipped.size() - ZIP_MAX_LISTED_SKIPS).append("개\n");
        }
        return sb.toString();
    }

    private static String extensionOf(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
