package com.jungwoo.project.memo.material;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 사진의 메타데이터(촬영 위치·기기·작성자 등)를 저장·외부 AI 전달 전에 뺀다. 재인코딩 없이 바이트 단위로 해당 구역만 들어낸다
 * — 화질과 글자 모양은 그대로다. 방향(EXIF Orientation)도 함께 빠지므로 회전된 사진은 회전된 채로 읽힌다(수용).
 *
 * <ul>
 *   <li>JPEG: APP1(EXIF·XMP)·APP13(IPTC/Photoshop) 세그먼트를 뺀다. 다른 세그먼트와 SOS 이후 이미지 데이터는 그대로.</li>
 *   <li>PNG: eXIf·tEXt·zTXt·iTXt 청크를 뺀다.</li>
 *   <li>WebP: EXIF·XMP 청크를 빼고 VP8X 플래그와 RIFF 크기를 고친다.</li>
 * </ul>
 * 구조가 예상과 다르면(잘린 파일 등) 원본을 돌려주지 않고 실패한다 — 메타데이터가 남은 채로 보내지 않는다.
 */
public final class ImageMetadataStripper {

    private static final Set<String> PNG_DROP = Set.of("eXIf", "tEXt", "zTXt", "iTXt");

    public static class StripException extends RuntimeException {
        StripException(String message) {
            super(message);
        }
    }

    private ImageMetadataStripper() {
    }

    public static byte[] strip(byte[] bytes, ImageFormat format) {
        return switch (format) {
            case JPEG -> jpeg(bytes);
            case PNG -> png(bytes);
            case WEBP -> webp(bytes);
        };
    }

    static byte[] jpeg(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            throw new StripException("JPEG 시작 표지 없음");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 2);
        int i = 2;
        while (i < b.length) {
            if (i + 1 >= b.length || (b[i] & 0xFF) != 0xFF) {
                throw new StripException("JPEG 세그먼트 표지 없음·잘림");
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { // 채움 바이트
                i++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) { // SOS 이후(이미지 데이터)·EOI는 그대로 붙인다
                out.write(b, i, b.length - i);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // 길이 없는 표지
                out.write(b, i, 2);
                i += 2;
                continue;
            }
            if (i + 4 > b.length) {
                throw new StripException("JPEG 세그먼트 잘림");
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > b.length) {
                throw new StripException("JPEG 세그먼트 길이 이상");
            }
            boolean drop = marker == 0xE1 || marker == 0xED;
            if (!drop) {
                out.write(b, i, 2 + len);
            }
            i += 2 + len;
        }
        throw new StripException("JPEG 이미지 데이터 없음");
    }

    static byte[] png(byte[] b) {
        if (b.length < 8) {
            throw new StripException("PNG 시그니처 없음");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int i = 8;
        while (i < b.length) {
            if (i + 12 > b.length) {
                throw new StripException("PNG 청크 잘림");
            }
            long len = ByteBuffer.wrap(b, i, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
            String type = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            long total = 12 + len;
            if (i + total > b.length) {
                throw new StripException("PNG 청크 길이 이상");
            }
            if (!PNG_DROP.contains(type)) {
                out.write(b, i, (int) total);
            }
            i += (int) total;
            if (type.equals("IEND")) {
                return out.toByteArray();
            }
        }
        throw new StripException("PNG IEND 없음");
    }

    static byte[] webp(byte[] b) {
        if (b.length < 12) {
            throw new StripException("WebP 머리 없음");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        int i = 12;
        int vp8xAt = -1;
        while (i < b.length) {
            if (i + 8 > b.length) {
                throw new StripException("WebP 청크 잘림");
            }
            String type = new String(b, i, 4, StandardCharsets.ISO_8859_1);
            long len = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            long total = 8 + len + (len & 1); // 홀수 길이는 1바이트 채움
            if (i + 8 + len > b.length) {
                throw new StripException("WebP 청크 길이 이상");
            }
            int copy = (int) Math.min(total, b.length - i);
            if (!type.equals("EXIF") && !type.equals("XMP ")) {
                if (type.equals("VP8X")) {
                    vp8xAt = body.size();
                }
                body.write(b, i, copy);
            }
            i += copy;
        }
        byte[] chunks = body.toByteArray();
        if (vp8xAt >= 0 && vp8xAt + 8 < chunks.length) {
            chunks[vp8xAt + 8] = (byte) (chunks[vp8xAt + 8] & ~0x0C); // EXIF(0x08)·XMP(0x04) 플래그 끔
        }
        ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        head.put(b, 0, 4).putInt(4 + chunks.length).put(b, 8, 4);
        byte[] out = new byte[12 + chunks.length];
        System.arraycopy(head.array(), 0, out, 0, 12);
        System.arraycopy(chunks, 0, out, 12, chunks.length);
        return out;
    }
}
