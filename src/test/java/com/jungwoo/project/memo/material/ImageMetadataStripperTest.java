package com.jungwoo.project.memo.material;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상담 사진의 메타데이터(촬영 위치 등)는 저장·외부 AI 전달 전에 빠진다. 이미지는 그대로 읽힌다(재인코딩 없음).
 * 이미지는 테스트 중에 코드로 만든다(실제 사진 없음).
 */
class ImageMetadataStripperTest {

    private static final String SECRET = "GPS 37.5665 126.9780 홍길동의 휴대폰";

    private static byte[] encode(String format) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(new String(needle.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1));
    }

    @Test
    void JPEG의_EXIF_세그먼트를_빼고_이미지는_그대로_읽힌다() throws Exception {
        byte[] jpeg = encode("jpg");
        byte[] payload = ("Exif\0\0" + SECRET).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(jpeg, 0, 2); // SOI
        withExif.write(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)});
        withExif.write(payload);
        withExif.write(jpeg, 2, jpeg.length - 2);
        byte[] input = withExif.toByteArray();
        assertThat(contains(input, SECRET)).isTrue();

        byte[] out = ImageMetadataStripper.strip(input, ImageFormat.JPEG);

        assertThat(contains(out, SECRET)).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(out)).getWidth()).isEqualTo(8);
        assertThat(ImageFormat.detect(out)).contains(ImageFormat.JPEG);
    }

    @Test
    void PNG의_글_청크를_빼고_이미지는_그대로_읽힌다() throws Exception {
        byte[] png = encode("png");
        byte[] text = ("Author\0" + SECRET).getBytes(StandardCharsets.UTF_8);
        ByteBuffer chunk = ByteBuffer.allocate(12 + text.length).order(ByteOrder.BIG_ENDIAN);
        chunk.putInt(text.length).put("tEXt".getBytes(StandardCharsets.ISO_8859_1)).put(text);
        CRC32 crc = new CRC32();
        crc.update("tEXt".getBytes(StandardCharsets.ISO_8859_1));
        crc.update(text);
        chunk.putInt((int) crc.getValue());
        // IHDR(8+25) 바로 뒤에 넣는다.
        ByteArrayOutputStream withText = new ByteArrayOutputStream();
        withText.write(png, 0, 33);
        withText.write(chunk.array());
        withText.write(png, 33, png.length - 33);
        byte[] input = withText.toByteArray();
        assertThat(contains(input, SECRET)).isTrue();

        byte[] out = ImageMetadataStripper.strip(input, ImageFormat.PNG);

        assertThat(contains(out, SECRET)).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(out)).getHeight()).isEqualTo(8);
    }

    @Test
    void WebP의_EXIF_청크를_빼고_플래그와_크기를_고친다() {
        byte[] exif = SECRET.getBytes(StandardCharsets.UTF_8);
        byte[] vp8l = {0x2F, 0, 0, 0, 0}; // 구조만 보는 최소 비트스트림(5바이트, 홀수라 채움 1바이트)
        ByteBuffer b = ByteBuffer.allocate(12 + 18 + 8 + exif.length + (exif.length & 1) + 8 + vp8l.length + 1)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(b.capacity() - 8).put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        b.put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(10).put((byte) 0x08).put(new byte[9]);
        b.put("EXIF".getBytes(StandardCharsets.ISO_8859_1)).putInt(exif.length).put(exif);
        if ((exif.length & 1) == 1) {
            b.put((byte) 0);
        }
        b.put("VP8L".getBytes(StandardCharsets.ISO_8859_1)).putInt(vp8l.length).put(vp8l).put((byte) 0);
        byte[] input = b.array();
        assertThat(ImageFormat.detect(input)).contains(ImageFormat.WEBP);

        byte[] out = ImageMetadataStripper.strip(input, ImageFormat.WEBP);

        assertThat(contains(out, SECRET)).isFalse();
        ByteBuffer o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(o.getInt(4)).isEqualTo(out.length - 8);
        assertThat(out[20] & 0x08).isZero(); // VP8X EXIF 플래그 꺼짐
        assertThat(new String(out, 30, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8L");
    }

    @Test
    void 구조가_깨진_파일은_원본을_돌려주지_않고_실패한다() {
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x10, 0x00, 'E'};
        assertThatThrownBy(() -> ImageMetadataStripper.strip(truncated, ImageFormat.JPEG))
                .isInstanceOf(ImageMetadataStripper.StripException.class);
        byte[] endsWithMarker = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        assertThatThrownBy(() -> ImageMetadataStripper.strip(endsWithMarker, ImageFormat.JPEG))
                .isInstanceOf(ImageMetadataStripper.StripException.class);
    }

    @Test
    void 형식은_앞머리로_정하고_이미지라는_주장은_확장자나_content_type_하나면_된다() throws Exception {
        assertThat(ImageFormat.detect(encode("png"))).contains(ImageFormat.PNG);
        assertThat(ImageFormat.detect("%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(ImageFormat.claimedImage("image.png", null)).isTrue();
        assertThat(ImageFormat.claimedImage("blob", "image/jpeg; charset=binary")).isTrue();
        assertThat(ImageFormat.claimedImage("notes.pdf", "application/pdf")).isFalse();
    }
}
