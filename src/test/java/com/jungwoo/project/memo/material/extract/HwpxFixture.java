package com.jungwoo.project.memo.material.extract;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 테스트용 HWPX(OWPML) 합성 파일. 한컴 오피스 없이 형식 그대로 만든다.
 *
 * <p>여기서 만드는 XML은 hwpxlib가 실제로 파싱하는 것과 같은 구조다(문단·run·t, 표는 tbl/tr/tc/subList).
 * 실제 한컴 파일 대신 쓰는 이유는 저작권 있는 문서를 저장소에 넣지 않기 위해서다 — 진짜 파일로 하는
 * 확인은 별도의 수동 검증으로 남긴다.
 */
public final class HwpxFixture {

    private final List<String> paragraphs = new ArrayList<>();
    private List<List<String>> table;
    private boolean withDoctype;
    private boolean withoutMimetype;
    private int paddingBytes;

    private HwpxFixture() {
    }

    public static HwpxFixture create() {
        return new HwpxFixture();
    }

    public HwpxFixture paragraph(String text) {
        paragraphs.add(text);
        return this;
    }

    /** 표 하나. 행 × 셀 문자열. */
    public HwpxFixture table(List<List<String>> rows) {
        this.table = rows;
        return this;
    }

    /** section XML 앞머리에 DOCTYPE 선언을 넣는다(외부 엔티티 차단 검증용). */
    public HwpxFixture withDoctype() {
        this.withDoctype = true;
        return this;
    }

    public HwpxFixture withoutMimetype() {
        this.withoutMimetype = true;
        return this;
    }

    /** 압축 해제 크기 상한 검증용 채움 바이트. */
    public HwpxFixture padding(int bytes) {
        this.paddingBytes = bytes;
        return this;
    }

    public Path write(Path file) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            if (!withoutMimetype) {
                put(zip, "mimetype", "application/hwp+zip");
            }
            put(zip, "version.xml", VERSION);
            put(zip, "META-INF/container.xml", CONTAINER);
            put(zip, "Contents/content.hpf", CONTENT_HPF);
            put(zip, "Contents/header.xml", HEADER);
            put(zip, "Contents/section0.xml", section());
            if (paddingBytes > 0) {
                zip.putNextEntry(new ZipEntry("BinData/padding.bin"));
                byte[] chunk = new byte[64 * 1024];
                for (int written = 0; written < paddingBytes; written += chunk.length) {
                    zip.write(chunk);
                }
                zip.closeEntry();
            }
        }
        return file;
    }

    private String section() {
        StringBuilder sb = new StringBuilder();
        sb.append(withDoctype
                ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE sec [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\"")
                .append(" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\">\n");
        int id = 1;
        for (String paragraph : paragraphs) {
            sb.append(paragraph(id++, paragraph));
        }
        if (table != null) {
            sb.append("<hp:p id=\"").append(id++).append("\" paraPrIDRef=\"0\" styleIDRef=\"0\"><hp:run charPrIDRef=\"0\">\n")
                    .append(table()).append("</hp:run></hp:p>\n");
        }
        return sb.append("</hs:sec>\n").toString();
    }

    private static String paragraph(int id, String text) {
        return "<hp:p id=\"" + id + "\" paraPrIDRef=\"0\" styleIDRef=\"0\"><hp:run charPrIDRef=\"0\">"
                + "<hp:t>" + escape(text) + "</hp:t></hp:run></hp:p>\n";
    }

    private String table() {
        StringBuilder sb = new StringBuilder();
        int rows = table.size();
        int cols = table.stream().mapToInt(List::size).max().orElse(0);
        sb.append("<hp:tbl id=\"100\" zOrder=\"0\" numberingType=\"TABLE\" textWrap=\"TOP_AND_BOTTOM\"")
                .append(" textFlow=\"BOTH_SIDES\" lock=\"0\" dropcapstyle=\"None\" pageBreak=\"CELL\" repeatHeader=\"1\"")
                .append(" rowCnt=\"").append(rows).append("\" colCnt=\"").append(cols).append("\"")
                .append(" cellSpacing=\"0\" borderFillIDRef=\"1\" noAdjust=\"0\">\n");
        int paraId = 1000;
        for (int r = 0; r < rows; r++) {
            sb.append("<hp:tr>\n");
            List<String> row = table.get(r);
            for (int c = 0; c < row.size(); c++) {
                sb.append("<hp:tc name=\"\" header=\"0\" hasMargin=\"0\" protect=\"0\" editable=\"0\" dirty=\"0\"")
                        .append(" borderFillIDRef=\"1\">\n")
                        .append("<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"TOP\"")
                        .append(" linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"0\" textHeight=\"0\"")
                        .append(" hasTextRef=\"0\" hasNumRef=\"0\">\n")
                        .append(paragraph(paraId++, row.get(c)))
                        .append("</hp:subList>\n")
                        .append("<hp:cellAddr colAddr=\"").append(c).append("\" rowAddr=\"").append(r).append("\"/>")
                        .append("<hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/>")
                        .append("<hp:cellSz width=\"1000\" height=\"300\"/>\n")
                        .append("</hp:tc>\n");
            }
            sb.append("</hp:tr>\n");
        }
        return sb.append("</hp:tbl>\n").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String VERSION = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <hv:HCFVersion xmlns:hv="http://www.hancom.co.kr/hwpml/2011/version" tagetApplication="WORDPROCESSOR"
             major="5" minor="0" micro="5" buildNumber="0" os="10" xmlVersion="1.4"
             application="Hancom Office Hangul" appVersion="9, 1, 1, 5656 WIN32LEWindows_Unknown_Version"/>
            """;

    private static final String CONTAINER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container"
             xmlns:hv="http://www.hancom.co.kr/hwpml/2011/version">
              <ocf:rootfiles>
                <ocf:rootfile full-path="Contents/content.hpf" media-type="application/hwpml-package+xml"/>
              </ocf:rootfiles>
            </ocf:container>
            """;

    private static final String CONTENT_HPF = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf/" xmlns:ha="http://www.hancom.co.kr/hwpml/2011/app"
             version="" unique-identifier="" id="">
              <opf:metadata><opf:title>fixture</opf:title></opf:metadata>
              <opf:manifest>
                <opf:item id="header" href="Contents/header.xml" media-type="application/xml"/>
                <opf:item id="section0" href="Contents/section0.xml" media-type="application/xml"/>
              </opf:manifest>
              <opf:spine><opf:itemref idref="section0" linear="yes"/></opf:spine>
            </opf:package>
            """;

    private static final String HEADER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" version="1.4" secCnt="1">
              <hh:refList/>
            </hh:head>
            """;
}
