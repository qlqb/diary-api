package com.jungwoo.project.memo.material;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 자료로 올릴 수 있는 파일 형식. 확장자·저장할 content type·업로드 검증 규칙을 한곳에 둔다.
 *
 * <p>PDF·PPTX는 브라우저가 content type을 안정적으로 보내므로 그 값을 검사한다. HWP·IPYNB·ZIP은
 * 그렇지 않다 — 같은 zip이 Windows Chrome에서는 application/x-zip-compressed, 다른 곳에서는
 * application/zip으로 오고, hwp·ipynb는 한컴오피스·주피터 설치 여부에 따라 빈 값(→ octet-stream)이
 * 오기도 한다. 그래서 이 셋은 보낸 content type을 믿지 않고 파일 앞머리 시그니처를 확인하며,
 * DB에는 아래의 대표 content type을 저장한다.
 */
enum MaterialFileFormat {

    PDF("pdf", "application/pdf", true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", true),
    /** HWP 5.0(OLE 복합 문서). HWP 3.0 이하·HWPX는 받지 않는다. */
    HWP("hwp", "application/x-hwp", false),
    IPYNB("ipynb", "application/x-ipynb+json", false),
    ZIP("zip", "application/zip", false);

    /** 시그니처 확인에 읽는 앞머리 길이. ipynb는 BOM·공백 뒤의 '{'를 봐야 해서 넉넉히 읽는다. */
    static final int HEADER_BYTES = 64;

    private static final byte[] OLE_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_LOCAL_HEADER = {'P', 'K', 3, 4};
    /** 항목이 하나도 없는 zip. 받기는 하되 추출 단계에서 "읽을 문서 없음"이 된다. */
    private static final byte[] ZIP_EMPTY = {'P', 'K', 5, 6};

    private final String extension;
    private final String contentType;
    private final boolean trustsClientContentType;

    MaterialFileFormat(String extension, String contentType, boolean trustsClientContentType) {
        this.extension = extension;
        this.contentType = contentType;
        this.trustsClientContentType = trustsClientContentType;
    }

    String extension() {
        return extension;
    }

    String contentType() {
        return contentType;
    }

    static Optional<MaterialFileFormat> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(ext)).findFirst();
    }

    static Set<String> extensions() {
        return Set.copyOf(Arrays.stream(values()).map(MaterialFileFormat::extension).toList());
    }

    /**
     * 업로드를 받아도 되는지. content type을 믿는 형식은 그 값만, 믿지 않는 형식은 앞머리만 본다.
     *
     * @param header 파일의 처음 최대 {@link #HEADER_BYTES}바이트
     */
    boolean accepts(String clientContentType, byte[] header) {
        if (trustsClientContentType) {
            return contentType.equals(clientContentType);
        }
        return switch (this) {
            case HWP -> startsWith(header, OLE_SIGNATURE);
            case ZIP -> startsWith(header, ZIP_LOCAL_HEADER) || startsWith(header, ZIP_EMPTY);
            case IPYNB -> looksLikeJsonObject(header);
            default -> false;
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

    /** UTF-8 BOM과 공백을 건너뛴 첫 글자가 '{'인가. 노트북 파일은 JSON 객체다. */
    private static boolean looksLikeJsonObject(byte[] header) {
        String head = new String(header, StandardCharsets.UTF_8);
        if (head.startsWith("﻿")) {
            head = head.substring(1);
        }
        return head.stripLeading().startsWith("{");
    }
}
