package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.MaterialFileFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 압축 파일을 안전하게 읽는다. 목록을 만들고, 고른 항목 하나를 임시 파일로 푼다.
 *
 * <p>여기서 지키는 것:
 * <ul>
 *   <li><b>경로</b>: 절대 경로·`..` 이탈·역슬래시·심볼릭 링크 항목은 고를 수 없다. 애초에 압축 안의
 *       경로를 저장 위치로 쓰지 않지만(저장명은 UUID다), 그런 항목은 정상적인 자료 묶음이 아니므로
 *       이유를 붙여 목록에 남긴다.</li>
 *   <li><b>압축 폭탄</b>: 항목 수·항목 크기·전체 해제 크기에 상한을 둔다. 헤더의 크기 값은 참고만 하고,
 *       실제로 풀면서 읽은 바이트로 다시 센다.</li>
 *   <li><b>이름 인코딩</b>: UTF-8 플래그가 있으면 UTF-8, 없으면 CP949(한글 Windows 압축)로 읽는다.
 *       판단할 수 없으면 그 항목을 고를 수 없게 두고 이유를 남긴다 — 추측해서 엉뚱한 이름으로 저장하지 않는다.</li>
 *   <li><b>중첩 압축</b>: .zip 항목은 이번 범위가 아니다(지원하지 않음으로 표시). PPTX·HWPX는 속이
 *       zip이지만 문서 형식이므로 정상 대상이다.</li>
 * </ul>
 */
@Slf4j
@Component
public class ZipArchiveReader {

    private static final Charset CP949 = Charset.forName("MS949");
    /** OS가 끼워 넣는 메타데이터. 자료가 아니므로 목록에서 아예 뺀다. */
    private static final Set<String> NOISE_NAMES = Set.of(".ds_store", "thumbs.db", "desktop.ini");

    /**
     * 목록의 항목 하나. 아직 DB 행이 아니다.
     *
     * @param supported  고를 수 있는가
     * @param skipReason 고를 수 없는 이유(supported=false일 때만)
     */
    public record ArchiveEntry(int index, String path, String displayName, String extension, long sizeBytes,
                               boolean supported, String skipReason) {
    }

    public record Listing(List<ArchiveEntry> entries, int selectableCount, List<String> warnings) {
    }

    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxSelectable) {
    }

    /**
     * 압축 목록을 만든다. 파일을 풀지 않고 헤더만 읽는다 — 목록 준비는 빨라야 하고, 실제 해제는
     * 사용자가 고른 항목에만 한다.
     */
    public Listing list(Path archive, Limits limits) {
        try (ZipFile zip = open(archive)) {
            List<ArchiveEntry> entries = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            Set<String> seenPaths = new HashSet<>();
            Set<String> duplicatePaths = new HashSet<>();
            int index = 0;
            int selectable = 0;
            int total = 0;

            Enumeration<ZipArchiveEntry> it = zip.getEntries();
            while (it.hasMoreElements()) {
                ZipArchiveEntry entry = it.nextElement();
                total++;
                if (total > limits.maxEntries()) {
                    warnings.add("압축 안 항목이 " + limits.maxEntries() + "개를 넘어 그 뒤는 읽지 않았어요");
                    break;
                }
                String path = entry.getName();
                if (entry.isDirectory() || isNoise(path)) {
                    continue;
                }
                String reason = rejectReason(entry, path);
                String normalized = normalize(path);
                if (reason == null && !seenPaths.add(normalized)) {
                    // 같은 경로가 두 번 들어 있는 압축. 어느 쪽이 맞는지 알 수 없으므로 둘 다 고를 수 없게 한다.
                    duplicatePaths.add(normalized);
                    reason = "같은 경로가 압축 안에 두 번 있어요";
                }
                boolean supported = reason == null && selectable < limits.maxSelectable();
                if (reason == null && !supported) {
                    reason = "한 번에 고를 수 있는 " + limits.maxSelectable() + "개를 넘었어요";
                }
                if (supported) {
                    selectable++;
                }
                entries.add(new ArchiveEntry(index++, path, displayName(path), extensionOf(path),
                        Math.max(entry.getSize(), 0), supported, reason));
            }
            // 중복 경로는 먼저 통과한 쪽도 고를 수 없게 되돌린다.
            if (!duplicatePaths.isEmpty()) {
                List<ArchiveEntry> fixed = new ArrayList<>(entries.size());
                for (ArchiveEntry entry : entries) {
                    if (entry.supported() && duplicatePaths.contains(normalize(entry.path()))) {
                        fixed.add(new ArchiveEntry(entry.index(), entry.path(), entry.displayName(),
                                entry.extension(), entry.sizeBytes(), false, "같은 경로가 압축 안에 두 번 있어요"));
                        selectable--;
                    } else {
                        fixed.add(entry);
                    }
                }
                entries = fixed;
            }
            return new Listing(entries, selectable, warnings);
        } catch (IOException e) {
            throw new ZipArchiveException("압축 파일을 열지 못했어요 (손상됐거나 암호가 걸렸을 수 있어요)", e);
        }
    }

    /**
     * 항목 하나를 임시 파일로 푼다. 헤더가 말한 크기를 믿지 않고 읽은 바이트를 세며, 상한을 넘으면
     * 중간에 멈추고 임시 파일을 지운다.
     *
     * @return 푼 임시 파일. 호출부가 쓰고 나서 지운다.
     */
    public Path extractEntry(Path archive, String entryPath, Path targetDir, Limits limits) throws IOException {
        try (ZipFile zip = open(archive)) {
            ZipArchiveEntry entry = findEntry(zip, entryPath);
            if (entry == null) {
                throw new ZipArchiveException("압축 안에서 그 파일을 찾지 못했어요", null);
            }
            String reason = rejectReason(entry, entry.getName());
            if (reason != null) {
                throw new ZipArchiveException(reason, null);
            }
            Files.createDirectories(targetDir);
            // 저장 이름은 압축 안 이름과 무관하다 — 확장자만 가져온다(경로 조작의 여지를 없앤다).
            Path target = Files.createTempFile(targetDir, "entry-", "." + extensionOf(entry.getName()));
            long copied = 0;
            try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    copied += n;
                    if (copied > limits.maxEntryBytes()) {
                        throw new ZipArchiveException("파일 하나가 상한("
                                + (limits.maxEntryBytes() / (1024 * 1024)) + "MiB)을 넘어요", null);
                    }
                    out.write(buffer, 0, n);
                }
            } catch (Exception e) {
                Files.deleteIfExists(target);
                throw e;
            }
            return target;
        }
    }

    private ZipArchiveEntry findEntry(ZipFile zip, String entryPath) {
        Enumeration<ZipArchiveEntry> it = zip.getEntries();
        while (it.hasMoreElements()) {
            ZipArchiveEntry entry = it.nextElement();
            if (entry.getName().equals(entryPath)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 이름 인코딩: 항목에 UTF-8 플래그가 있으면 commons-compress가 UTF-8로 읽는다. 플래그가 없으면
     * 여기서 준 CP949로 읽는다 — 한글 Windows 압축이 그렇게 만든다.
     */
    private ZipFile open(Path archive) throws IOException {
        return ZipFile.builder().setPath(archive).setCharset(CP949).setUseUnicodeExtraFields(true).get();
    }

    /** 고를 수 없는 이유. null이면 가져올 수 있다. */
    private String rejectReason(ZipArchiveEntry entry, String path) {
        if (entry.isUnixSymlink()) {
            return "심볼릭 링크는 가져오지 않아요";
        }
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:[\\\\/].*")) {
            return "절대 경로 항목이라 가져오지 않아요";
        }
        String normalized = normalize(path);
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return "압축 밖을 가리키는 경로라 가져오지 않아요";
            }
        }
        if (normalized.isBlank()) {
            return "경로가 비어 있어요";
        }
        if (path.indexOf(' ') >= 0 || path.chars().anyMatch(c -> c == 0xFFFD)) {
            // 인코딩을 판단하지 못해 대체 문자가 섞였다. 추측해서 이름을 정하지 않는다.
            return "파일 이름의 인코딩을 알 수 없어요";
        }
        String extension = extensionOf(path);
        if ("zip".equals(extension)) {
            return "압축 안의 압축은 이번에 가져오지 않아요";
        }
        if (MaterialFileFormat.fromExtension(extension).isEmpty()) {
            return "지원하지 않는 형식이에요";
        }
        return null;
    }

    private static boolean isNoise(String path) {
        if (path.startsWith("__MACOSX/")) {
            return true;
        }
        String base = displayName(path).toLowerCase(Locale.ROOT);
        return NOISE_NAMES.contains(base) || base.startsWith("._") || base.startsWith("~$");
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String displayName(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String extensionOf(String path) {
        String base = displayName(path);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 압축 자체를 다룰 수 없을 때. 사용자에게 그대로 보여줄 문장을 담는다. */
    public static class ZipArchiveException extends RuntimeException {
        public ZipArchiveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
