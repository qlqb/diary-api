package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.extract.DocumentExtractionException;
import com.jungwoo.project.memo.material.extract.DocumentExtractionService;
import com.jungwoo.project.memo.material.extract.ExtractedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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

    /**
     * extracted_text로 저장할 수 있는 최대 크기(UTF-8 바이트). 자료 행 INSERT는 한 쿼리라 DB의
     * max_allowed_packet(로컬 MariaDB 기본 1MiB)을 넘으면 자료 자체가 만들어지지 않는다. 다른 열과
     * 인코딩 여유를 두고 그 아래로 자른다. 분석은 단위(material_text_units, 행마다 따로 저장)를 읽으므로
     * 여기서 잘라도 분석 범위는 줄지 않는다 — 전체 텍스트는 링크 제안·상담이 앞부분만 쓰는 사본이다.
     */
    static final int MAX_STORED_TEXT_BYTES = 800_000;

    public Outcome extract(Path file, String extension, Long userId, String fileHash) {
        return fitForStorage(extractUnbounded(file, extension, userId, fileHash));
    }

    private Outcome extractUnbounded(Path file, String extension, Long userId, String fileHash) {
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

    /** 저장 한도를 넘는 전체 텍스트는 잘라 저장하고, 잘랐다는 사실을 경고로 남긴다. */
    static Outcome fitForStorage(Outcome outcome) {
        String text = outcome.text();
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length <= MAX_STORED_TEXT_BYTES) {
            return outcome;
        }
        // 앞에서부터 UTF-8 바이트를 세어 한도 직전에서 자른다. 서로게이트 쌍(4바이트)은 가르지 않는다.
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > MAX_STORED_TEXT_BYTES) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
        }
        String notice = "본문 사본이 저장 한도를 넘어 앞부분만 보관했어요(자동 분석은 전체를 읽어요)";
        String warning = outcome.warning() == null ? notice : outcome.warning() + " · " + notice;
        return new Outcome(outcome.status(), text.substring(0, end), outcome.error(),
                warning.length() > 500 ? warning.substring(0, 500) : warning, outcome.units(), outcome.pageCount());
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
