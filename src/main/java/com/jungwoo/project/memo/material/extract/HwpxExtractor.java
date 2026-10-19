package com.jungwoo.project.memo.material.extract;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 한글 문서(HWPX, OWPML XML)에서 본문과 표를 읽는다. 파서는 hwpxlib(Apache-2.0)이다.
 *
 * <p>HWPX는 XML 묶음을 담은 zip이다. 그래서 파싱 전에 두 가지를 직접 확인한다.
 * <ul>
 *   <li><b>외부 엔티티</b>: 모든 XML 항목의 앞머리에 DOCTYPE·ENTITY 선언이 있으면 읽지 않고 거부한다.
 *       파서에 넘긴 뒤에는 외부 DTD·엔티티 해석 여부를 우리가 정할 수 없다.</li>
 *   <li><b>압축 폭탄</b>: 항목 수와 해제 크기를 세어 상한을 넘으면 거부한다. 헤더의 크기 값은 믿지 않고
 *       실제로 읽으며 센다.</li>
 * </ul>
 *
 * <p>section XML 번호는 페이지가 아니다 — 쪽 나눔이 아니라 구역 나눔이라 한 section이 수십 쪽일 수
 * 있다. 위치는 HWP와 같은 "구간 N"을 쓰고 pageCount는 비운다.
 */
@Slf4j
@Component
public class HwpxExtractor {

    private static final int PROLOG_SCAN_BYTES = 4096;

    public ExtractedDocument extract(Path file) {
        verifyContainer(file);
        HWPXFile hwpx = read(file);
        ExtractedDocument.Builder builder = ExtractedDocument.builder();
        TextBlockCollector blocks = new TextBlockCollector(builder);
        for (SectionXMLFile section : hwpx.sectionXMLFileList().items()) {
            appendParaList(blocks, section);
            if (blocks.isTruncated()) {
                break;
            }
        }
        blocks.flush();
        return builder.build();
    }

    private HWPXFile read(Path file) {
        try {
            HWPXFile hwpx = HWPXReader.fromFile(file.toFile());
            if (hwpx == null || hwpx.sectionXMLFileList() == null) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                        "HWPX 문서가 아니에요");
            }
            return hwpx;
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("password") || message.contains("encrypt")) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.ENCRYPTED, null, e);
            }
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "HWPX 문서를 읽지 못했어요 (형식이 다르거나 파일이 손상됐을 수 있어요)", e);
        }
    }

    /** zip 컨테이너 검사: 항목 수·해제 크기 상한과 XML 외부 엔티티 선언. */
    private void verifyContainer(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            int entries = 0;
            long total = 0;
            boolean hasMimetype = false;
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (++entries > ExtractionLimits.MAX_CONTAINER_ENTRIES) {
                    throw new DocumentExtractionException(DocumentExtractionException.Reason.TOO_LARGE,
                            "HWPX 안의 항목이 너무 많아요");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                hasMimetype |= "mimetype".equals(name);
                try (InputStream in = zip.getInputStream(entry)) {
                    long read = countAndScan(in, name.toLowerCase(Locale.ROOT).endsWith(".xml"), name);
                    total += read;
                }
                if (total > ExtractionLimits.MAX_CONTAINER_TOTAL_BYTES) {
                    throw new DocumentExtractionException(DocumentExtractionException.Reason.TOO_LARGE,
                            "HWPX를 풀어 읽는 크기가 상한을 넘었어요");
                }
            }
            if (!hasMimetype) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                        "HWPX 문서가 아니에요 (mimetype 항목이 없습니다)");
            }
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (ZipException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "HWPX 파일을 열지 못했어요 (손상됐거나 암호가 걸렸을 수 있어요)", e);
        } catch (IOException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "HWPX 파일을 읽지 못했어요", e);
        }
    }

    /** 실제로 읽은 바이트를 센다. xml이면 앞머리에 DOCTYPE·ENTITY가 있는지도 본다. */
    private long countAndScan(InputStream in, boolean xml, String name) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long read = 0;
        boolean prologChecked = !xml;
        StringBuilder prolog = xml ? new StringBuilder() : null;
        int n;
        while ((n = in.read(buffer)) > 0) {
            read += n;
            if (read > ExtractionLimits.MAX_CONTAINER_ENTRY_BYTES) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.TOO_LARGE,
                        "HWPX 안의 " + name + " 항목이 너무 커요");
            }
            if (!prologChecked) {
                prolog.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (prolog.length() >= PROLOG_SCAN_BYTES) {
                    rejectExternalEntities(prolog, name);
                    prologChecked = true;
                }
            }
        }
        if (!prologChecked) {
            rejectExternalEntities(prolog, name);
        }
        return read;
    }

    private void rejectExternalEntities(StringBuilder prolog, String name) {
        String head = prolog.toString().toUpperCase(Locale.ROOT);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "HWPX 안의 " + name + "에 외부 엔티티 선언이 있어 읽지 않았어요");
        }
    }

    private void appendParaList(TextBlockCollector blocks, ParaListCore paraList) {
        for (Para para : paraList.paras()) {
            if (blocks.isTruncated()) {
                return;
            }
            StringBuilder text = new StringBuilder();
            List<Table> tables = new ArrayList<>();
            for (int i = 0; i < para.countOfRun(); i++) {
                Run run = para.getRun(i);
                if (run == null) {
                    continue;
                }
                for (RunItem item : run.runItems()) {
                    if (item instanceof T t) {
                        text.append(textOf(t));
                    } else if (item instanceof Table table) {
                        tables.add(table);
                    }
                }
            }
            blocks.addParagraph(text.toString());
            for (Table table : tables) {
                blocks.addAtomic(TableText.render(rows(table)));
            }
        }
    }

    private String textOf(T t) {
        if (t.isOnlyText()) {
            return t.onlyText() == null ? "" : t.onlyText();
        }
        StringBuilder sb = new StringBuilder();
        for (TItem item : t.items()) {
            if (item instanceof NormalText normal && normal.text() != null) {
                sb.append(normal.text());
            }
        }
        return sb.toString();
    }

    private List<List<String>> rows(Table table) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < table.countOfTr(); r++) {
            Tr tr = table.getTr(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < tr.countOfTc(); c++) {
                Tc tc = tr.getTc(c);
                cells.add(tc == null || tc.subList() == null ? "" : cellText(tc.subList()));
            }
            rows.add(cells);
        }
        return rows;
    }

    private String cellText(ParaListCore cell) {
        StringBuilder sb = new StringBuilder();
        for (Para para : cell.paras()) {
            for (int i = 0; i < para.countOfRun(); i++) {
                Run run = para.getRun(i);
                if (run == null) {
                    continue;
                }
                for (RunItem item : run.runItems()) {
                    if (item instanceof T t) {
                        sb.append(textOf(t));
                    }
                }
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
