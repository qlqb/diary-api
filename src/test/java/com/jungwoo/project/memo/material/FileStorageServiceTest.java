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
 * 업로드 형식 검증. PDF·PPTX는 브라우저가 보낸 content type을, HWP·IPYNB·ZIP은 파일 앞머리를 본다.
 * 저장되는 content type은 형식의 대표 값이다.
 */
class FileStorageServiceTest {

    private static final byte[] OLE_HEADER = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};

    @TempDir
    Path uploadDir;

    private final FileStorageService service = new FileStorageService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 20L * 1024 * 1024);
    }

    @Test
    void zip_isAccepted_whateverContentTypeTheBrowserSends() throws Exception {
        // Windows Chrome은 zip을 application/x-zip-compressed로 보낸다.
        FileStorageService.StoredFile stored = service.store(1L, new MockMultipartFile(
                "file", "3주차.zip", "application/x-zip-compressed", new byte[]{'P', 'K', 3, 4, 20, 0}));

        assertThat(stored.extension()).isEqualTo("zip");
        assertThat(stored.contentType()).isEqualTo("application/zip");
        assertThat(stored.storedFilename()).endsWith(".zip");
        assertThat(Files.exists(uploadDir.resolve(stored.storagePath()))).isTrue();
    }

    @Test
    void hwp_isAccepted_asOctetStream_whenItIsAnOleDocument() {
        FileStorageService.StoredFile stored = service.store(1L, new MockMultipartFile(
                "file", "강의계획서.HWP", "application/octet-stream", OLE_HEADER));

        assertThat(stored.extension()).isEqualTo("hwp");
        assertThat(stored.contentType()).isEqualTo("application/x-hwp");
    }

    @Test
    void ipynb_isAccepted_withBomAndLeadingWhitespace() {
        byte[] body = ("﻿\n  {\"cells\": []}").getBytes(StandardCharsets.UTF_8);

        FileStorageService.StoredFile stored = service.store(1L, new MockMultipartFile(
                "file", "lab.ipynb", "", body));

        assertThat(stored.contentType()).isEqualTo("application/x-ipynb+json");
    }

    @Test
    void newFormats_withTheWrongSignature_areRejected() {
        assertRejected(new MockMultipartFile("file", "a.hwp", "application/x-hwp", "HWP Document File V3.00".getBytes()));
        assertRejected(new MockMultipartFile("file", "a.zip", "application/zip", "%PDF-1.4".getBytes()));
        assertRejected(new MockMultipartFile("file", "a.ipynb", "application/json", "print('x')".getBytes()));
    }

    @Test
    void pdf_stillRequiresItsContentType() {
        assertRejected(new MockMultipartFile("file", "a.pdf", "application/octet-stream", "%PDF-1.4".getBytes()));
        assertThat(service.store(1L, new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .contentType()).isEqualTo("application/pdf");
    }

    @Test
    void otherExtensions_areRejected() {
        assertRejected(new MockMultipartFile("file", "a.hwpx", "application/zip", new byte[]{'P', 'K', 3, 4}));
        assertRejected(new MockMultipartFile("file", "a.docx", "application/zip", new byte[]{'P', 'K', 3, 4}));
    }

    private void assertRejected(MockMultipartFile file) {
        assertThatThrownBy(() -> service.store(1L, file))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
}
