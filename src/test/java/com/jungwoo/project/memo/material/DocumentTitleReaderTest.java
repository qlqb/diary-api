package com.jungwoo.project.memo.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파일 속성 제목. 본문과 따로 읽고, 없거나 못 읽어도 실패가 아니다.
 */
class DocumentTitleReaderTest {

    private final DocumentTitleReader reader = new DocumentTitleReader();

    @TempDir
    Path dir;

    @Test
    void PDF_문서_정보의_제목을_읽는다() throws Exception {
        Path pdf = pdf("aws.pdf", "AWS 구성하기 + SSH 실습 (네트워크프로그래밍 3주차)");

        assertThat(reader.read(pdf, "pdf")).isEqualTo("AWS 구성하기 + SSH 실습 (네트워크프로그래밍 3주차)");
    }

    @Test
    void 제목이_없으면_null이고_실패하지_않는다() throws Exception {
        assertThat(reader.read(pdf("plain.pdf", null), "pdf")).isNull();
        assertThat(reader.read(pdf("blank.pdf", "   "), "pdf")).isNull();
    }

    @Test
    void 깨진_파일이나_모르는_형식은_null이다() throws Exception {
        Path broken = dir.resolve("broken.pdf");
        Files.writeString(broken, "not a pdf");

        assertThat(reader.read(broken, "pdf")).isNull();
        assertThat(reader.read(broken, "sh")).isNull();
        assertThat(reader.read(dir.resolve("missing.pdf"), "pdf")).isNull();
        assertThat(DocumentTitleReader.supports("sh")).isFalse();
        assertThat(DocumentTitleReader.supports("PDF")).isTrue();
    }

    @Test
    void 공백을_정리하고_길이를_자른다() {
        assertThat(DocumentTitleReader.clean("  AWS\n 실습\t ")).isEqualTo("AWS 실습");
        assertThat(DocumentTitleReader.clean("가".repeat(400))).hasSize(DocumentTitleReader.MAX_TITLE);
    }

    private Path pdf(String name, String title) throws Exception {
        Path path = dir.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            if (title != null) {
                document.getDocumentInformation().setTitle(title);
            }
            document.save(path.toFile());
        }
        return path;
    }
}
