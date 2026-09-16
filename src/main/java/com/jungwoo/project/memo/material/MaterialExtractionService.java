package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.extract.DocumentExtractionException;
import com.jungwoo.project.memo.material.extract.DocumentExtractionService;
import com.jungwoo.project.memo.material.extract.ExtractedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 업로드·재추출이 쓰는 단일 진입점. 형식에 맞는 추출기를 골라 "전체 텍스트 + 단위 + 경고"를 한 번에 준다.
 *
 * <p>형식별 경로:
 * <ul>
 *   <li>PDF·PPTX: 기존 {@link TextExtractionService}(전체 텍스트) + {@link MaterialTextUnitService}(페이지·슬라이드 단위).</li>
 *   <li>IPYNB·HWP·HWPX: {@link DocumentExtractionService}가 한 번 읽어 둘 다 만든다.</li>
 * </ul>
 *
 * <p>단위가 없는데 텍스트는 있는 경우(예: 페이지 단위 추출만 실패한 PDF)에만 전체 텍스트를 블록으로
 * 나눈다 — 위치를 "구간 N"이라고 말하는 마지막 수단이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialExtractionService {

    private final TextExtractionService textExtractionService;
    private final MaterialTextUnitService materialTextUnitService;
    private final DocumentExtractionService documentExtractionService;

    /**
     * @param warning 성공했지만 일부를 읽지 못한 경우의 안내. 없으면 null.
     */
    public record Outcome(ExtractionStatus status, String text, String error, String warning,
                          List<MaterialTextUnit> units, Integer pageCount) {

        public boolean success() {
            return status == ExtractionStatus.SUCCESS;
        }
    }

    public Outcome extract(Path file, String extension, Long userId, String fileHash) {
        if (DocumentExtractionService.handles(extension)) {
            return extractDocument(file, extension, userId, fileHash);
        }
        TextExtractionService.ExtractionResult result = textExtractionService.extract(file, extension);
        if (result.status() != ExtractionStatus.SUCCESS) {
            return new Outcome(result.status(), null, result.error(), null, List.of(), null);
        }
        MaterialTextUnitService.Extracted extracted =
                materialTextUnitService.extractUnits(file, extension, userId, null, fileHash);
        List<MaterialTextUnit> units = extracted.units();
        if (units.isEmpty()) {
            units = materialTextUnitService.blocksFromText(result.text(), userId, null, fileHash);
        }
        return new Outcome(ExtractionStatus.SUCCESS, result.text(), null, null, units, extracted.pageCount());
    }

    private Outcome extractDocument(Path file, String extension, Long userId, String fileHash) {
        ExtractedDocument document;
        try {
            document = documentExtractionService.extract(file, extension);
        } catch (DocumentExtractionException e) {
            log.info("원문 추출 실패: extension={}, reason={}, message={}", extension, e.reason(), e.getMessage());
            return new Outcome(ExtractionStatus.FAILED, null, e.getMessage(), null, List.of(), null);
        }
        if (!document.hasText()) {
            return new Outcome(ExtractionStatus.FAILED_NO_TEXT, null, noTextMessage(extension), null, List.of(), null);
        }
        List<MaterialTextUnit> units = document.units().stream()
                .map(unit -> MaterialTextUnit.builder()
                        .userId(userId).fileHash(fileHash)
                        .unitType(unit.type()).unitNo(unit.unitNo())
                        .charCount(unit.text().length()).text(unit.text())
                        .build())
                .toList();
        // unit_index는 저장 직전에 붙인다 — 같은 셀이 여러 단위로 나뉘어도 순서가 곧 index다.
        for (int i = 0; i < units.size(); i++) {
            units.get(i).setUnitIndex(i);
        }
        return new Outcome(ExtractionStatus.SUCCESS, document.fullText(), null, warning(document), units,
                document.pageCount());
    }

    private static String warning(ExtractedDocument document) {
        if (document.warnings().isEmpty()) {
            return null;
        }
        String joined = String.join(" · ", document.warnings());
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }

    private static String noTextMessage(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase()) {
            case "ipynb" -> "노트북에 읽을 수 있는 셀 내용이 없어요";
            case "hwp", "hwpx" -> "문서에 글자가 없어요 (그림으로만 된 문서일 수 있어요)";
            default -> "문서에서 텍스트를 추출하지 못했어요";
        };
    }
}
