package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 자료를 물리 단위(PDF 페이지 / PPTX 슬라이드)로 다시 읽어 material_text_units에 남긴다.
 *
 * <p>기존 {@link TextExtractionService}는 문서 전체를 한 문자열로 돌려주고 PDF에는 페이지 표식이
 * 없다. 구간 분석이 "몇 페이지"를 말하려면 단위가 필요하다. 업로드 직후에는 파일이 있으니
 * 파일에서 만들고, 원본이 없는 옛 자료는 extracted_text를 문자 수 기준 블록으로 나눈다 —
 * 그 경우 위치는 "구간 N"이지 페이지가 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialTextUnitService {

    /** 한 단위의 상한. 이보다 긴 페이지는 같은 unit_no로 여러 행이 된다. */
    static final int MAX_UNIT_CHARS = 12_000;
    /** 원본 없이 extracted_text만 있을 때의 블록 크기. */
    static final int TEXT_BLOCK_CHARS = 6_000;

    private final MaterialTextUnitMapper unitMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final FileStorageService fileStorageService;

    public record Extracted(List<MaterialTextUnit> units, Integer pageCount) {
    }

    /**
     * 파일에서 단위를 만든다. 저장하지 않는다 — 업로드 흐름이 트랜잭션 안에서 저장한다.
     * 실패하면 빈 목록(추출 실패는 기존 TextExtractionService가 별도로 기록한다).
     */
    public Extracted extractUnits(Path filePath, String extension, Long userId, Long materialId, String fileHash) {
        try {
            return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
                case "pdf" -> pdfUnits(filePath, userId, materialId, fileHash);
                case "pptx" -> pptxUnits(filePath, userId, materialId, fileHash);
                default -> new Extracted(List.of(), null);
            };
        } catch (Exception e) {
            log.warn("단위 추출 실패: materialId={}, type={}", materialId, e.getClass().getSimpleName());
            return new Extracted(List.of(), null);
        }
    }

    /** extracted_text만 있을 때(원본 없음). 위치 정보는 없고 "구간 N"이다. */
    public List<MaterialTextUnit> blocksFromText(String text, Long userId, Long materialId, String fileHash) {
        List<MaterialTextUnit> units = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return units;
        }
        int no = 1;
        int index = 0;
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(text.length(), pos + TEXT_BLOCK_CHARS);
            // 단락 경계에서 자른다 — 문장을 반으로 가르면 겹침으로도 못 살린다.
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > pos + TEXT_BLOCK_CHARS / 2) {
                    end = nl + 1;
                }
            }
            String part = text.substring(pos, end);
            units.add(unit(userId, materialId, fileHash, index++, TextUnitType.TEXT_BLOCK, no++, part));
            pos = end;
        }
        return units;
    }

    @Transactional
    public void saveUnits(List<MaterialTextUnit> units) {
        for (MaterialTextUnit unit : units) {
            unitMapper.insert(unit);
        }
    }

    /**
     * 분석 직전에 단위가 있는지 확인하고 없으면 만든다(기존 자료 백필). 파일이 있으면 파일에서,
     * 없으면 extracted_text에서. 해시가 비어 있으면(옛 자료) 디스크에서 계산해 채운다.
     *
     * @return 단위 목록. 만들 수 없으면(원문도 파일도 없음) 빈 목록
     */
    @Transactional
    public List<MaterialTextUnit> ensureUnits(CourseMaterial material) {
        String hash = material.getFileHash();
        Path path = readablePath(material);
        if (hash == null && path != null) {
            hash = sha256(path);
            if (hash != null && courseMaterialMapper.fillFileHashIfMissing(material.getMaterialId(), hash) > 0) {
                material.setFileHash(hash);
            }
        }
        if (hash == null) {
            return List.of();
        }
        List<MaterialTextUnit> existing = unitMapper.findByMaterialIdAndHash(material.getMaterialId(), hash);
        if (!existing.isEmpty()) {
            return existing;
        }
        List<MaterialTextUnit> units;
        if (path != null) {
            Extracted extracted = extractUnits(path, extensionOf(material), material.getUserId(),
                    material.getMaterialId(), hash);
            units = extracted.units();
            if (extracted.pageCount() != null && material.getPageCount() == null) {
                courseMaterialMapper.updatePageCount(material.getMaterialId(), material.getUserId(), extracted.pageCount());
                material.setPageCount(extracted.pageCount());
            }
        } else {
            units = List.of();
        }
        if (units.isEmpty() && material.getExtractedText() != null) {
            units = blocksFromText(material.getExtractedText(), material.getUserId(), material.getMaterialId(), hash);
        }
        if (units.isEmpty()) {
            return List.of();
        }
        try {
            saveUnits(units);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 두 worker가 같은 자료의 단위를 동시에 만들었다. 먼저 저장된 쪽을 쓴다.
            return unitMapper.findByMaterialIdAndHash(material.getMaterialId(), hash);
        }
        return units;
    }

    public boolean isExtractable(CourseMaterial material) {
        return material.getExtractionStatus() == ExtractionStatus.SUCCESS;
    }

    // ===== PDF / PPTX =====

    private Extracted pdfUnits(Path filePath, Long userId, Long materialId, String fileHash) throws IOException {
        List<MaterialTextUnit> units = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            int index = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text == null || text.isBlank()) {
                    continue; // 그림만 있는 페이지. 단위를 만들지 않는다 — 읽은 척하지 않는다.
                }
                for (String part : splitLong(text)) {
                    units.add(unit(userId, materialId, fileHash, index++, TextUnitType.PDF_PAGE, page, part));
                }
            }
            return new Extracted(units, pages);
        }
    }

    private Extracted pptxUnits(Path filePath, Long userId, Long materialId, String fileHash) throws IOException {
        List<MaterialTextUnit> units = new ArrayList<>();
        try (InputStream in = Files.newInputStream(filePath);
             XMLSlideShow slideShow = new XMLSlideShow(in)) {
            int slideNo = 0;
            int index = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                slideNo++;
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append('\n');
                        }
                    }
                }
                if (sb.length() == 0) {
                    continue;
                }
                for (String part : splitLong(sb.toString())) {
                    units.add(unit(userId, materialId, fileHash, index++, TextUnitType.PPTX_SLIDE, slideNo, part));
                }
            }
            return new Extracted(units, slideNo);
        }
    }

    private static List<String> splitLong(String text) {
        if (text.length() <= MAX_UNIT_CHARS) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(text.length(), pos + MAX_UNIT_CHARS);
            parts.add(text.substring(pos, end));
            pos = end;
        }
        return parts;
    }

    private static MaterialTextUnit unit(Long userId, Long materialId, String fileHash, int index,
                                         TextUnitType type, int no, String text) {
        return MaterialTextUnit.builder()
                .userId(userId).materialId(materialId).fileHash(fileHash)
                .unitIndex(index).unitType(type).unitNo(no)
                .charCount(text.length()).text(text)
                .build();
    }

    private Path readablePath(CourseMaterial material) {
        if (material.getStoragePath() == null) {
            return null;
        }
        try {
            Path path = fileStorageService.resolve(material.getStoragePath());
            return Files.isReadable(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extensionOf(CourseMaterial material) {
        String name = material.getStoredFilename() != null ? material.getStoredFilename() : material.getOriginalFilename();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    public static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
