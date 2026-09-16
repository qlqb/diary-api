package com.jungwoo.project.memo.material.zip;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 압축 읽기의 방어선. 여기서 막지 못하면 압축 하나로 디스크 밖에 파일을 쓰거나(경로 이탈),
 * 디스크를 채우거나(압축 폭탄), 엉뚱한 이름의 자료를 만들게 된다.
 */
class ZipArchiveReaderTest {

    private final ZipArchiveReader reader = new ZipArchiveReader();

    private static final ZipArchiveReader.Limits LIMITS =
            new ZipArchiveReader.Limits(1000, 20L * 1024 * 1024, 200L * 1024 * 1024, 100);

    private static Path zip(Path dir, String name, Charset charset, List<String[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file), charset)) {
            for (String[] entry : entries) {
                out.putNextEntry(new ZipEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void 지원_형식만_고를_수_있고_나머지는_이유가_붙는다(@TempDir Path dir) throws Exception {
        Path file = zip(dir, "week3.zip", StandardCharsets.UTF_8, List.of(
                new String[]{"3주차/강의.pdf", "%PDF-1.4"},
                new String[]{"3주차/슬라이드.pptx", "PK"},
                new String[]{"3주차/실습.ipynb", "{}"},
                new String[]{"3주차/안내.hwp", "x"},
                new String[]{"3주차/안내.hwpx", "PK"},
                new String[]{"3주차/데이터.csv", "a,b"},
                new String[]{"3주차/실행.exe", "MZ"},
                new String[]{"3주차/코드.py", "print(1)"},
                new String[]{"3주차/묶음.zip", "PK"},
                new String[]{"__MACOSX/3주차/._강의.pdf", "x"},
                new String[]{"3주차/.DS_Store", "x"}));

        ZipArchiveReader.Listing listing = reader.list(file, LIMITS);

        assertThat(listing.selectableCount()).isEqualTo(5);
        assertThat(listing.entries()).extracting(ZipArchiveReader.ArchiveEntry::path,
                        ZipArchiveReader.ArchiveEntry::supported)
                .contains(tuple("3주차/강의.pdf", true), tuple("3주차/슬라이드.pptx", true),
                        tuple("3주차/실습.ipynb", true), tuple("3주차/안내.hwp", true),
                        tuple("3주차/안내.hwpx", true),
                        tuple("3주차/데이터.csv", false), tuple("3주차/실행.exe", false),
                        tuple("3주차/코드.py", false), tuple("3주차/묶음.zip", false));
        // OS 메타데이터는 목록에도 남기지 않는다 — 사용자가 고를 일이 없는 잡음이다.
        assertThat(listing.entries()).extracting(ZipArchiveReader.ArchiveEntry::path)
                .noneMatch(p -> p.contains("__MACOSX") || p.contains("DS_Store"));
        assertThat(listing.entries()).filteredOn(e -> e.path().endsWith(".zip"))
                .extracting(ZipArchiveReader.ArchiveEntry::skipReason)
                .containsExactly("압축 안의 압축은 이번에 가져오지 않아요");
    }

    @Test
    void 경로_이탈과_절대_경로는_고를_수_없다(@TempDir Path dir) throws Exception {
        Path file = zip(dir, "evil.zip", StandardCharsets.UTF_8, List.of(
                new String[]{"../../etc/passwd.pdf", "%PDF-1.4"},
                new String[]{"/tmp/absolute.pdf", "%PDF-1.4"},
                new String[]{"C:/windows/win.pdf", "%PDF-1.4"},
                new String[]{"정상/강의.pdf", "%PDF-1.4"}));

        ZipArchiveReader.Listing listing = reader.list(file, LIMITS);

        assertThat(listing.selectableCount()).isEqualTo(1);
        assertThat(listing.entries()).filteredOn(e -> !e.supported())
                .extracting(ZipArchiveReader.ArchiveEntry::skipReason)
                .allSatisfy(reason -> assertThat(reason).matches(".*(압축 밖|절대 경로).*"));
        // 디스크에는 압축 안 경로로 아무것도 쓰지 않는다 — 목록 만들기는 파일을 풀지 않는다.
        try (var files = Files.list(dir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString).toList()).containsExactly("evil.zip");
        }
    }

    @Test
    void 심볼릭_링크_항목은_가져오지_않는다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("link.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(file.toFile())) {
            ZipArchiveEntry link = new ZipArchiveEntry("자료/링크.pdf");
            link.setUnixMode(0120777); // symlink
            out.putArchiveEntry(link);
            out.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            ZipArchiveEntry normal = new ZipArchiveEntry("자료/강의.pdf");
            normal.setUnixMode(0100644);
            out.putArchiveEntry(normal);
            out.write("%PDF-1.4".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        ZipArchiveReader.Listing listing = reader.list(file, LIMITS);

        assertThat(listing.entries()).filteredOn(e -> e.path().endsWith("링크.pdf"))
                .extracting(ZipArchiveReader.ArchiveEntry::skipReason)
                .containsExactly("심볼릭 링크는 가져오지 않아요");
        assertThat(listing.selectableCount()).isEqualTo(1);
    }

    @Test
    void 같은_경로가_두_번_있으면_둘_다_고를_수_없다(@TempDir Path dir) throws Exception {
        // java.util.zip은 같은 이름을 두 번 쓰지 못하게 막는다. 실제로 그런 압축은 존재하므로
        // commons-compress로 직접 만든다.
        Path file = dir.resolve("dup.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(file.toFile())) {
            for (String[] entry : List.<String[]>of(
                    new String[]{"3주차/강의.pdf", "%PDF-1.4 첫 번째"},
                    new String[]{"3주차/강의.pdf", "%PDF-1.4 두 번째"},
                    new String[]{"3주차/실습.ipynb", "{}"})) {
                out.putArchiveEntry(new ZipArchiveEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }

        ZipArchiveReader.Listing listing = reader.list(file, LIMITS);

        assertThat(listing.selectableCount()).isEqualTo(1);
        assertThat(listing.entries()).filteredOn(e -> e.path().endsWith("강의.pdf"))
                .extracting(ZipArchiveReader.ArchiveEntry::supported,
                        ZipArchiveReader.ArchiveEntry::skipReason)
                .containsOnly(tuple(false, "같은 경로가 압축 안에 두 번 있어요"));
    }

    @Test
    void 한글_이름은_UTF8이든_CP949이든_그대로_읽는다(@TempDir Path dir) throws Exception {
        Path utf8 = zip(dir, "utf8.zip", StandardCharsets.UTF_8,
                List.<String[]>of(new String[]{"3주차/강의계획서.pdf", "%PDF-1.4"}));
        Path cp949 = zip(dir, "cp949.zip", Charset.forName("MS949"),
                List.<String[]>of(new String[]{"3주차/강의계획서.pdf", "%PDF-1.4"}));

        assertThat(reader.list(utf8, LIMITS).entries()).singleElement()
                .extracting(ZipArchiveReader.ArchiveEntry::path).isEqualTo("3주차/강의계획서.pdf");
        assertThat(reader.list(cp949, LIMITS).entries()).singleElement()
                .extracting(ZipArchiveReader.ArchiveEntry::path).isEqualTo("3주차/강의계획서.pdf");
    }

    @Test
    void 항목_수_상한을_넘으면_거기서_멈추고_알린다(@TempDir Path dir) throws Exception {
        List<String[]> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(new String[]{"많은자료/" + i + ".pdf", "%PDF-1.4"});
        }
        Path file = zip(dir, "many.zip", StandardCharsets.UTF_8, entries);

        ZipArchiveReader.Listing listing = reader.list(file,
                new ZipArchiveReader.Limits(5, 1024, 4096, 100));

        assertThat(listing.entries()).hasSize(5);
        assertThat(listing.warnings()).anyMatch(w -> w.contains("5개를 넘어"));
    }

    @Test
    void 고를_수_있는_개수_상한을_넘으면_그_뒤는_이유가_붙는다(@TempDir Path dir) throws Exception {
        List<String[]> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new String[]{"자료/" + i + ".pdf", "%PDF-1.4"});
        }
        Path file = zip(dir, "cap.zip", StandardCharsets.UTF_8, entries);

        ZipArchiveReader.Listing listing = reader.list(file,
                new ZipArchiveReader.Limits(1000, 1024, 4096, 2));

        assertThat(listing.selectableCount()).isEqualTo(2);
        assertThat(listing.entries()).filteredOn(e -> !e.supported())
                .extracting(ZipArchiveReader.ArchiveEntry::skipReason)
                .allSatisfy(reason -> assertThat(reason).contains("2개를 넘었어요"));
    }

    @Test
    void 헤더가_작다고_말해도_실제로_읽으며_세고_상한에서_멈춘다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bomb.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("자료/거대.pdf"));
            byte[] chunk = new byte[1024 * 1024];
            chunk[0] = '%';
            for (int i = 0; i < 6; i++) {
                out.write(chunk);
            }
            out.closeEntry();
        }
        ZipArchiveReader.Limits small = new ZipArchiveReader.Limits(1000, 2L * 1024 * 1024, 4L * 1024 * 1024, 100);

        assertThatThrownBy(() -> reader.extractEntry(file, "자료/거대.pdf", dir.resolve("out"), small))
                .isInstanceOf(ZipArchiveReader.ZipArchiveException.class)
                .hasMessageContaining("상한");
        // 중간에 멈추고 임시 파일도 남기지 않는다.
        if (Files.exists(dir.resolve("out"))) {
            try (var files = Files.list(dir.resolve("out"))) {
                assertThat(files.toList()).isEmpty();
            }
        }
    }

    @Test
    void 고른_항목은_임시_파일로_풀리고_이름은_압축_안_이름과_무관하다(@TempDir Path dir) throws Exception {
        Path file = zip(dir, "ok.zip", StandardCharsets.UTF_8,
                List.<String[]>of(new String[]{"3주차/강의계획서.pdf", "%PDF-1.4 본문"}));

        Path extracted = reader.extractEntry(file, "3주차/강의계획서.pdf", dir.resolve("tmp"), LIMITS);

        assertThat(Files.readString(extracted)).isEqualTo("%PDF-1.4 본문");
        assertThat(extracted.getFileName().toString()).startsWith("entry-").endsWith(".pdf");
        assertThat(extracted.getParent()).isEqualTo(dir.resolve("tmp"));
    }

    @Test
    void 압축이_아니면_열_때_이유를_말한다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.zip");
        Files.writeString(file, "이건 압축이 아니다");

        assertThatThrownBy(() -> reader.list(file, LIMITS))
                .isInstanceOf(ZipArchiveReader.ZipArchiveException.class)
                .hasMessageContaining("압축 파일을 열지 못했어요");
    }
}
