package com.jungwoo.project.memo.material.extract;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 한글 문서(HWP 5.x 바이너리)에서 본문과 표를 읽는다. 파서는 hwplib(Apache-2.0)이다.
 *
 * <p>hwplib의 TextExtractor를 그대로 쓰지 않고 객체 모델을 직접 걷는 이유는 표 때문이다 —
 * TextExtractor는 표 안 글자를 문단 사이에 끼워 넣기만 해서 행·셀 관계가 사라진다. 강의계획서의
 * 주차표가 정확히 그 모양이라, 여기서는 {@link TableText} 형식으로 옮긴다.
 *
 * <p>지원 범위: HWP 5.0 형식(파일 앞머리가 OLE 복합 문서). HWP 3.0 이하와 암호·배포용 잠금 문서는
 * 읽지 않고 이유를 남긴다. 그림 안의 글자는 읽지 않는다(OCR은 범위 밖) — 본문이 비면 그렇게 말한다.
 */
@Slf4j
@Component
public class HwpExtractor {

    public ExtractedDocument extract(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return extract(in);
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "한글 문서를 읽지 못했어요", e);
        }
    }

    public ExtractedDocument extract(InputStream in) {
        return extractFrom(read(in));
    }

    /** 이미 읽어 둔 문서에서 본문을 뽑는다. 테스트가 파일로 쓰지 않고 객체 모델만으로 검증할 수 있게 열어 둔다. */
    public ExtractedDocument extractFrom(HWPFile hwp) {
        if (hwp.getFileHeader() != null
                && (hwp.getFileHeader().hasPassword() || hwp.getFileHeader().isEncryptPublicCertification())) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.ENCRYPTED);
        }
        ExtractedDocument.Builder builder = ExtractedDocument.builder();
        TextBlockCollector blocks = new TextBlockCollector(builder);
        for (Section section : hwp.getBodyText().getSectionList()) {
            for (Paragraph paragraph : section) {
                appendParagraph(blocks, paragraph);
                if (blocks.isTruncated()) {
                    break;
                }
            }
        }
        blocks.flush();
        // 쪽수는 파일에 없다(편집기가 배치로 계산한다). 문단 수를 페이지 수인 것처럼 넣지 않는다.
        return builder.build();
    }

    private HWPFile read(InputStream in) {
        HWPFile hwp;
        try {
            hwp = HWPReader.fromInputStream(in);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (message.contains("password") || message.contains("encrypt")) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.ENCRYPTED, null, e);
            }
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "한글 문서(HWP 5.0)로 읽을 수 없어요. HWP 3.0 이하이거나 파일이 손상됐을 수 있어요", e);
        }
        if (hwp == null || hwp.getBodyText() == null) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "한글 문서(HWP 5.0)가 아니에요");
        }
        return hwp;
    }

    private void appendParagraph(TextBlockCollector blocks, Paragraph paragraph) {
        blocks.addParagraph(normalString(paragraph));
        if (paragraph.getControlList() == null) {
            return;
        }
        for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlTable table) {
                blocks.addAtomic(TableText.render(rows(table)));
            }
        }
    }

    private List<List<String>> rows(ControlTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : table.getRowList()) {
            List<String> cells = new ArrayList<>();
            for (Cell cell : row.getCellList()) {
                cells.add(cellText(cell.getParagraphList()));
            }
            rows.add(cells);
        }
        return rows;
    }

    private String cellText(ParagraphList paragraphs) {
        if (paragraphs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Paragraph paragraph : paragraphs) {
            String text = normalString(paragraph);
            if (text != null && !text.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text.trim());
            }
            // 셀 안에 또 표가 있는 경우(중첩 표)는 글자만 이어 붙인다. 칸 구조까지 겹쳐 그리면
            // 바깥 표의 행이 오히려 읽기 어려워진다.
            if (paragraph.getControlList() != null) {
                for (Control control : paragraph.getControlList()) {
                    if (control instanceof ControlTable nested) {
                        for (Row row : nested.getRowList()) {
                            for (Cell nestedCell : row.getCellList()) {
                                String nestedText = cellText(nestedCell.getParagraphList());
                                if (!nestedText.isBlank()) {
                                    sb.append(sb.length() > 0 ? " " : "").append(nestedText);
                                }
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private String normalString(Paragraph paragraph) {
        if (paragraph == null || paragraph.getText() == null) {
            return "";
        }
        try {
            return paragraph.getNormalString();
        } catch (Exception e) {
            // 한 문단의 인코딩이 깨져도 문서 전체를 버리지 않는다.
            log.debug("HWP 문단 텍스트 변환 실패: {}", e.getClass().getSimpleName());
            return "";
        }
    }
}
