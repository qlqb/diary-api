package com.jungwoo.project.memo.material;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 자료로 저장할 수 있는 파일 형식. 확장자·저장할 content type·업로드 검증 규칙을 한곳에 둔다.
 *
 * <p>ZIP은 여기 없다 — 압축 파일은 자료가 아니라 "여러 자료를 가져오는 통로"라 별도 경로
 * ({@code /api/materials/zip-imports})가 받고, 안에서 꺼낸 파일이 각각 이 형식들로 저장된다.
 *
 * <p>검증은 확장자 + 콘텐츠 구조다. 브라우저가 보내는 content type은 형식마다 제각각이라
 * (같은 zip이 application/zip·x-zip-compressed로 오고, hwp·ipynb·hwpx는 빈 값이나
 * application/octet-stream으로 온다) 그 값만으로 정상 파일을 막지 않는다. 대신 파일 앞머리
 * 시그니처를 확인하고, DB에는 아래의 대표 content type을 저장한다.
 */
public enum MaterialFileFormat {

    PDF("pdf", "application/pdf", Signature.PDF),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", Signature.ZIP),
    /** HWP 5.0(OLE 복합 문서). HWP 3.0 이하는 읽지 못하고, 그건 추출 단계에서 이유와 함께 실패한다. */
    HWP("hwp", "application/x-hwp", Signature.OLE),
    /** HWPX(OWPML). 속은 XML 묶음을 담은 zip이다. */
    HWPX("hwpx", "application/hwp+zip", Signature.ZIP),
    IPYNB("ipynb", "application/x-ipynb+json", Signature.JSON),
    /**
     * 셸 스크립트. <b>실행하지 않는 텍스트 자료</b>로만 읽는다(PlainTextExtractor). 평문에는 시그니처가 없어 앞머리에
     * NUL 바이트가 없는지만 본다 — 실제로 글자로 풀리는지는 추출 단계가 판단한다.
     */
    SH("sh", "text/plain", Signature.TEXT);

    /** 시그니처 확인에 읽는 앞머리 길이. ipynb는 BOM·공백 뒤의 '{'를 봐야 해서 넉넉히 읽는다. */
    public static final int HEADER_BYTES = 64;

    enum Signature {
        PDF, OLE, ZIP, JSON, TEXT
    }

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] OLE_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_LOCAL_HEADER = {'P', 'K', 3, 4};
    /** 항목이 하나도 없는 zip. 형식으로는 맞지만 내용이 없어 추출 단계에서 실패한다. */
    private static final byte[] ZIP_EMPTY = {'P', 'K', 5, 6};

    private final String extension;
    private final String contentType;
    private final Signature signature;

    MaterialFileFormat(String extension, String contentType, Signature signature) {
        this.extension = extension;
        this.contentType = contentType;
        this.signature = signature;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<MaterialFileFormat> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(ext)).findFirst();
    }

    public static Optional<MaterialFileFormat> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? Optional.empty() : fromExtension(base.substring(dot + 1));
    }

    public static Set<String> extensions() {
        return Set.copyOf(Arrays.stream(values()).map(MaterialFileFormat::extension).toList());
    }

    /**
     * 파일 앞머리가 이 형식의 것인가. 확장자만 바꾼 파일을 걸러내는 1차 검증이고, 실제로 읽을 수
     * 있는지는 추출 단계가 판단한다(예: HWP 5.0이 아닌 OLE 문서).
     *
     * @param header 파일의 처음 최대 {@link #HEADER_BYTES}바이트
     */
    public boolean accepts(byte[] header) {
        return switch (signature) {
            case PDF -> startsWith(header, PDF_SIGNATURE);
            case OLE -> startsWith(header, OLE_SIGNATURE);
            case ZIP -> startsWith(header, ZIP_LOCAL_HEADER) || startsWith(header, ZIP_EMPTY);
            case JSON -> looksLikeJsonObject(header);
            case TEXT -> looksLikeText(header);
        };
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** 앞머리에 NUL이 없고, 잘 알려진 바이너리 시그니처(PDF·OLE·ZIP·ELF·PE)로 시작하지 않는가. */
    private static boolean looksLikeText(byte[] header) {
        if (header.length == 0) {
            return false;
        }
        for (byte b : header) {
            if (b == 0) {
                return false;
            }
        }
        return !startsWith(header, PDF_SIGNATURE) && !startsWith(header, OLE_SIGNATURE)
                && !startsWith(header, ZIP_LOCAL_HEADER) && !startsWith(header, new byte[]{0x7F, 'E', 'L', 'F'})
                && !startsWith(header, new byte[]{'M', 'Z'});
    }

    /** UTF-8 BOM과 공백을 건너뛴 첫 글자가 '{'인가. 노트북 파일은 JSON 객체다. */
    private static boolean looksLikeJsonObject(byte[] header) {
        String head = new String(header, StandardCharsets.UTF_8);
        if (head.startsWith("﻿")) {
            head = head.substring(1);
        }
        return head.stripLeading().startsWith("{");
    }
}
