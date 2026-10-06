package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 형식 검증. 확장자 + 파일 앞머리로 판단하고, content type은 형식의 대표 값으로 저장한다.
 *
 * <p>브라우저가 보낸 content type을 기준으로 삼지 않는 이유: 같은 파일이 환경마다 다른 값으로 온다
 * (zip은 application/zip·x-zip-compressed, hwp·ipynb·hwpx는 빈 값이나 octet-stream). 그 값으로 막으면
 * 정상 파일이 "지원하지 않는 형식"이 된다. 반대로 확장자만 보면 이름만 바꾼 파일이 통과한다.
 */
class FileStorageServiceTest {

    private static final byte[] OLE_HEADER = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
    private static final byte[] ZIP_HEADER = {'P', 'K', 3, 4, 20, 0};

    @TempDir
    Path uploadDir;

    private final FileStorageService service = new FileStorageService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 20L * 1024 * 1024);
    }

    @Test
    void 브라우저가_어떤_content_type을_보내든_앞머리가_맞으면_받는다() {
        FileStorageService.StoredFile zip = service.store(1L, new MockMultipartFile(
                "file", "강의.hwpx", "application/x-zip-compressed", ZIP_HEADER));
        FileStorageService.StoredFile hwp = service.store(1L, new MockMultipartFile(
                "file", "강의계획서.HWP", "application/octet-stream", OLE_HEADER));
        FileStorageService.StoredFile pdf = service.store(1L, new MockMultipartFile(
                "file", "강의.pdf", "application/octet-stream", "%PDF-1.4".getBytes()));

        assertThat(zip.contentType()).isEqualTo("application/hwp+zip");
        assertThat(hwp.contentType()).isEqualTo("application/x-hwp");
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(hwp.storedFilename()).endsWith(".hwp");
        assertThat(Files.exists(uploadDir.resolve(pdf.storagePath()))).isTrue();
    }

    @Test
    void ipynb은_BOM과_공백_뒤의_중괄호까지_본다() {
        byte[] body = "﻿\n  {\"cells\": []}".getBytes(StandardCharsets.UTF_8);

        FileStorageService.StoredFile stored = service.store(1L, new MockMultipartFile(
                "file", "lab.ipynb", "", body));

        assertThat(stored.contentType()).isEqualTo("application/x-ipynb+json");
    }

    @Test
    void 확장자만_바꾼_파일은_앞머리에서_걸린다() {
        assertRejected(new MockMultipartFile("file", "a.hwp", "application/x-hwp", "HWP Document File V3.00".getBytes()));
        assertRejected(new MockMultipartFile("file", "a.hwpx", "application/hwp+zip", OLE_HEADER));
        assertRejected(new MockMultipartFile("file", "a.ipynb", "application/json", "print('x')".getBytes()));
        assertRejected(new MockMultipartFile("file", "a.pdf", "application/pdf", "not a pdf".getBytes()));
        assertRejected(new MockMultipartFile("file", "a.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "not a zip".getBytes()));
    }

    @Test
    void 자료로_받지_않는_확장자는_거부한다() {
        // zip은 자료가 아니라 가져오기 경로로 간다. 여기서 받으면 "압축 하나가 자료 하나"가 돼 버린다.
        assertRejected(new MockMultipartFile("file", "3주차.zip", "application/zip", ZIP_HEADER));
        assertRejected(new MockMultipartFile("file", "보고서.docx", "application/zip", ZIP_HEADER));
        assertRejected(new MockMultipartFile("file", "확장자없음", "application/pdf", "%PDF-1.4".getBytes()));
    }

    @Test
    void 압축_원본은_자료_폴더와_섞이지_않는_곳에_임시_보관한다() {
        FileStorageService.StoredFile stored = service.storeArchive(1L,
                new MockMultipartFile("file", "3주차.zip", "application/zip", ZIP_HEADER), 20L * 1024 * 1024);

        assertThat(stored.storagePath()).startsWith("zip-imports/1/").endsWith(".zip");
        assertThat(Files.exists(uploadDir.resolve(stored.storagePath()))).isTrue();
        assertThat(stored.fileHash()).hasSize(64);
    }

    @Test
    void 압축_원본도_크기_상한을_넘으면_받지_않는다() {
        MockMultipartFile big = new MockMultipartFile("file", "big.zip", "application/zip", new byte[2048]);

        assertThatThrownBy(() -> service.storeArchive(1L, big, 1024))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 압축에서_푼_파일도_같은_규칙으로_저장한다() throws Exception {
        Path extracted = uploadDir.resolve("entry-1.pdf");
        Files.write(extracted, "%PDF-1.4 본문".getBytes(StandardCharsets.UTF_8));

        FileStorageService.StoredFile stored = service.storeFile(1L, "3주차 강의.pdf", extracted);

        assertThat(stored.contentType()).isEqualTo("application/pdf");
        // 저장 이름은 UUID다 — 압축 안 이름·경로를 파일시스템에 쓰지 않는다.
        assertThat(stored.storedFilename()).doesNotContain("강의").endsWith(".pdf");
        assertThat(stored.storagePath()).startsWith("1/");
    }

    @Test
    void 압축에서_푼_파일도_형식이_아니면_거부한다() throws Exception {
        Path extracted = uploadDir.resolve("entry-2.pdf");
        Files.write(extracted, "이건 PDF가 아니다".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.storeFile(1L, "가짜.pdf", extracted))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    private void assertRejected(MockMultipartFile file) {
        assertThatThrownBy(() -> service.store(1L, file))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
}
