package com.jungwoo.project.memo.material;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 상담 교재 사진으로 받을 수 있는 이미지 형식. {@link MaterialFileFormat}(자료함 일반 업로드)과 따로 둔다 — 자료함 업로드로
 * 이미지가 들어오는 길은 열지 않는다(이미지는 글자 읽기·원본 30일 보관 규칙을 타야 한다).
 *
 * <p>형식은 <b>앞머리 바이트</b>로 정한다. 붙여넣은 사진은 파일명이 "image.png"처럼 실제와 다를 수 있어, 확장자·content type은
 * "이미지라고 주장하는가"만 보고(둘 중 하나), 저장 확장자와 content type은 앞머리에서 알아낸 값을 쓴다.
 */
public enum ImageFormat {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    public static final int HEADER_BYTES = 16;

    private static final Set<String> CLAIMED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> CLAIMED_TYPES = Set.of("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp");

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** 앞머리 바이트로 형식을 안다. 셋 중 어느 것도 아니면 비어 있다. */
    public static Optional<ImageFormat> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** 업로드가 이미지라고 주장하는가(파일 확장자나 브라우저 content type 중 하나). */
    public static boolean claimedImage(String filename, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (CLAIMED_TYPES.contains(type)) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && CLAIMED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
